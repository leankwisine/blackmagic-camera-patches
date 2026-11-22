package app.blackmagic.patches.monitor

import app.blackmagic.patches.shared.Constants.COMPATIBILITY_BLACKMAGIC_CAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val VERSION = "3.5.2.0025"
private const val VERSION_CODE = 26111001
private const val EXTENSION = "Lapp/blackmagic/patches/monitor/RemotePreview;"
private const val SURFACE = "Landroid/view/Surface;"
private const val SIZE = "Landroid/util/Size;"

@Suppress("unused")
val remoteMonitorTransformPatch = bytecodePatch(
    name = "Remote monitor display transforms",
    description = "Adds rotation, aspect-ratio and scaling controls with touch focus for the selected remote camera preview.",
    default = false,
) {
    compatibleWith(Compatibility(
        name = requireNotNull(COMPATIBILITY_BLACKMAGIC_CAM.name),
        packageName = requireNotNull(COMPATIBILITY_BLACKMAGIC_CAM.packageName),
        apkFileType = COMPATIBILITY_BLACKMAGIC_CAM.apkFileType,
        appIconColor = COMPATIBILITY_BLACKMAGIC_CAM.appIconColor,
        signatures = COMPATIBILITY_BLACKMAGIC_CAM.signatures,
        targets = listOf(AppTarget(version = VERSION, versionCodes = mapOf(SupportedAbi.ARM64_V8A to VERSION_CODE))),
    ))
    extendWith("remote-monitor.mpe")

    execute {
        if (packageMetadata.versionName != VERSION || packageMetadata.versionCode != VERSION_CODE.toString()) {
            throw PatchException("Remote monitor display transforms requires Blackmagic Camera $VERSION ($VERSION_CODE).")
        }

        val created = RemoteSurfaceCreated.method
        val destroyed = RemoteSurfaceDestroyed.method
        val draw = PreviewPresentationDraw.method
        val fixedSize = RemoteSurfaceCreated.instructionMatches[1]
        val remoteAttach = RemoteSurfaceCreated.instructionMatches[3]
        val fixedCall = fixedSize.getInstruction<Instruction35c>()
        val attachCall = remoteAttach.getInstruction<Instruction35c>()
        val drawCall = PreviewPresentationDraw.instructionMatches[3]
        val drawRegisters = drawCall.getInstruction<Instruction35c>()

        val actualDrawCalls = draw.implementation?.instructions?.count { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == GL && reference.name == "glDrawArrays"
        }
        val outputFields = classDefBy(OUTPUT).fields.map { it.name to it.type }
        val programFields = classDefBy(PROGRAM).fields.map { it.name to it.type }
        if (created.implementation?.registerCount != 6 || destroyed.implementation?.registerCount != 5 ||
            draw.implementation?.registerCount != 15 || fixedCall.registerC != 0 || attachCall.registerD != 5 || attachCall.registerE != 4 ||
            attachCall.opcode != Opcode.INVOKE_VIRTUAL || fixedCall.opcode != Opcode.INVOKE_VIRTUAL ||
            drawRegisters.opcode != Opcode.INVOKE_STATIC || drawRegisters.registerCount != 3 || actualDrawCalls != 1 ||
            !outputFields.containsAll(listOf("b" to SURFACE, "s" to SIZE, "c" to SIZE)) ||
            !programFields.containsAll(listOf("b" to "I", "c" to "I"))
        ) {
            throw PatchException("Remote monitor surface or presentation draw bytecode changed in $VERSION.")
        }

        // This controller uses UUID-keyed views that can be moved between Compose containers.
        val controllerHooks = listOf(ControllerSurfaceFactory, ControllerSurfaceUpdate).map { fingerprint ->
            val method = fingerprint.method
            val addView = fingerprint.instructionMatches.last()
            val call = addView.getInstruction<Instruction35c>()
            if (call.opcode != Opcode.INVOKE_VIRTUAL || call.registerCount != 2) {
                throw PatchException("Remote controller surface attachment changed in $VERSION.")
            }
            val camera = fingerprint.instructionMatches.first().getInstruction<Instruction35c>().registerC
            Triple(method, addView.index + 1,
                "invoke-static {v${call.registerD}, v$camera}, $EXTENSION->register(Landroid/view/SurfaceView;$UUID_TYPE)V")
        }
        for ((method, index, registration) in controllerHooks) {
            method.addInstructions(index, registration)
        }

        // Insertion before the remote attach call stays inside the UUID/non-local branch.
        created.addInstructions(remoteAttach.index, "invoke-static {p1, p0}, $EXTENSION->bind($HOLDER$UUID_TYPE)V")
        created.addInstructions(
            fixedSize.index + 1,
            "invoke-static {v0, p1}, $EXTENSION->prepare(Landroid/view/SurfaceView;${HOLDER})V",
        )
        destroyed.addInstructions(0, "invoke-static {p1}, $EXTENSION->unbind(${HOLDER})V")

        val focusRequest = RemoteFocusRequest.method
        val focusResponse = RemoteFocusResponse.method
        val tap = PreviewFocusTap.method
        val tapBounds = PreviewFocusTap.instructionMatches[1]
        val tapConstructor = PreviewFocusTap.instructionMatches[3]
        val tapBoundsRead = tapBounds.getInstruction<TwoRegisterInstruction>()
        val tapReturn = tap.implementation?.instructions?.elementAtOrNull(tapConstructor.index + 1)
        val echoConstructor = RemoteFocusResponse.instructionMatches.last()
        val echoCall = echoConstructor.getInstruction<Instruction35c>()
        if (focusRequest.implementation?.registerCount != 7 || focusResponse.implementation?.registerCount != 24 ||
            tap.implementation?.registerCount != 15 || tapBoundsRead.registerA != 12 || tapBoundsRead.registerB != 10 ||
            tapReturn?.opcode != Opcode.RETURN_OBJECT || (tapReturn as? OneRegisterInstruction)?.registerA != 13 ||
            echoCall.opcode != Opcode.INVOKE_DIRECT || echoCall.registerD != 2
        ) {
            throw PatchException("Remote focus command or reticle bytecode changed in $VERSION.")
        }

        // Compose keeps the original tap for its immediate reticle. Only the point entering the
        // remote camera's existing orientation conversion is unprojected into the stock preview.
        focusRequest.addInstructions(
            0,
            """
                iget-object v0, p0, $REMOTE_CAMERA_MODEL->g:$REMOTE_CAMERAS
                invoke-virtual {v0}, $REMOTE_CAMERAS->v()$UUID_TYPE
                move-result-object v0
                invoke-static {v0, p1}, $EXTENSION->focusToStock($UUID_TYPE$FOCUS_POINT)$FOCUS_POINT
                move-result-object p1
            """.trimIndent(),
        )
        focusResponse.addInstructions(
            echoConstructor.index,
            """
                move-object/from16 v6, p1
                invoke-static {v6, v2}, $EXTENSION->focusToDisplay($UUID_TYPE$FOCUS_POINT)$FOCUS_POINT
                move-result-object v2
            """.trimIndent(),
        )

        // Keep the camera model in the now-unused v2 before the stock code reuses p0.
        tap.addInstructionsWithLabels(
            tapConstructor.index + 1,
            """
                instance-of v8, v2, $REMOTE_CAMERA_MODEL
                if-eqz v8, :stock_tap
                check-cast v2, $REMOTE_CAMERA_MODEL
                iget-object v2, v2, $REMOTE_CAMERA_MODEL->g:$REMOTE_CAMERAS
                invoke-virtual {v2}, $REMOTE_CAMERAS->v()$UUID_TYPE
                move-result-object v2
                invoke-static {v2, p3}, $EXTENSION->keepVideoTap($UUID_TYPE$FOCUS_POINT)$FOCUS_POINT
                move-result-object p3
                :stock_tap
                nop
            """.trimIndent(),
        )
        tap.addInstructions(tapBounds.index + 1, "iget-object v2, p0, Ldi1;->h:$CAMERA_MODEL")

        val decoderFormat = RemoteDecoderFormat.method
        val formatCheck = RemoteDecoderFormat.instructionMatches.last()
        val decoderFields = classDefBy(REMOTE_DECODER).fields.map { it.name to it.type }
        val callbackFields = classDefBy(CODEC_CALLBACK).fields.map { it.name to it.type }
        if (decoderFormat.implementation?.registerCount != 3 ||
            decoderFormat.implementation?.instructions?.getOrNull(formatCheck.index + 1)?.opcode != Opcode.RETURN_VOID ||
            ("i" to RENDERER) !in decoderFields || ("a" to REMOTE_DECODER) !in callbackFields
        ) {
            throw PatchException("Remote feed format callbacks changed in $VERSION.")
        }

        decoderFormat.addInstructions(
            formatCheck.index + 1,
            """
                iget-object p0, p0, $CODEC_CALLBACK->a:$REMOTE_DECODER
                iget-object p0, p0, $REMOTE_DECODER->i:$RENDERER
                invoke-static {p0, p2}, $EXTENSION->setDecodedFormat(Ljava/lang/Object;$MEDIA_FORMAT)V
            """.trimIndent(),
        )
        // GL shader uniforms are set by the stock draw. The extension updates only the video quad.
        val (mode, first, count) = listOf(drawRegisters.registerC, drawRegisters.registerD, drawRegisters.registerE)
        draw.replaceInstruction(drawCall.index, "invoke-static {v$mode, v$first, v$count}, $EXTENSION->draw(III)V")
        draw.addInstructionsWithLabels(
            0,
            """
                move-object v0, p0
                iget-object v1, p4, $OUTPUT->b:$SURFACE
                iget-object v2, p4, $OUTPUT->s:$SIZE
                if-nez v2, :buffer_size
                iget-object v2, p4, $OUTPUT->c:$SIZE
                :buffer_size
                iget v3, p5, $PROGRAM->b:I
                iget v4, p5, $PROGRAM->c:I
                move-object v5, p3
                invoke-static/range {v0 .. v5}, $EXTENSION->begin(Ljava/lang/Object;$SURFACE${SIZE}II[F)V
            """.trimIndent(),
        )
    }
}
