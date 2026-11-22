package app.blackmagic.patches.monitor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

internal const val RENDERER = "Lkb2;"
internal const val OUTPUT = "Lxga;"
internal const val PROGRAM = "Lk90;"
internal const val VIEW = "Le80;"
internal const val HOLDER = "Landroid/view/SurfaceHolder;"
internal const val GL = "Landroid/opengl/GLES20;"
internal const val LUT_EFFECT = "Lp67;"
internal const val FLOAT_LUT = "Lcom/blackmagicdesign/android/colorutils/luts/Float16Lut;"
internal const val LUT_PARSER = "Lcom/blackmagicdesign/android/colorutils/luts/Float16LutParser;"
internal const val MONITOR_LUT = "Lapp/blackmagic/patches/monitor/MonitorLut;"
internal const val CONTEXT = "Landroid/content/Context;"
internal const val URI = "Landroid/net/Uri;"
internal const val REMOTE_DECODER = "Lcj9;"
internal const val CODEC_CALLBACK = "Lzi9;"
internal const val MEDIA_FORMAT = "Landroid/media/MediaFormat;"

internal object RemoteSurfaceCreated : Fingerprint(
    definingClass = "Ld35;",
    name = "surfaceCreated",
    isStatic = false,
    parameters = listOf(HOLDER),
    returnType = "V",
    filters = listOf(
        fieldAccess(definingClass = "Ld35;", name = "C", type = VIEW, opcode = Opcode.IGET_OBJECT),
        methodCall(definingClass = VIEW, name = "a", parameters = listOf("I", "I"), returnType = "V"),
        methodCall(definingClass = "Lpea;", name = "h", parameters = listOf("Ljava/util/UUID;"), returnType = "Z"),
        methodCall(definingClass = "Lpea;", name = "m",
            parameters = listOf(HOLDER, "Ljava/util/UUID;", "Z"), returnType = "V"),
    ),
)

internal object RemoteSurfaceDestroyed : Fingerprint(
    definingClass = "Ld35;",
    name = "surfaceDestroyed",
    isStatic = false,
    parameters = listOf(HOLDER),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = HOLDER, name = "getSurface", returnType = "Landroid/view/Surface;"),
        methodCall(definingClass = "Lcj9;", name = "k", parameters = emptyList(), returnType = "V"),
    ),
)

internal object PreviewPresentationDraw : Fingerprint(
    definingClass = RENDERER,
    isStatic = false,
    parameters = listOf("I", "I", "[F", OUTPUT, PROGRAM),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = GL, name = "glBindTexture", parameters = listOf("I", "I"), returnType = "V"),
        methodCall(definingClass = PROGRAM, name = "a", parameters = listOf("[F"), returnType = "V"),
        methodCall(definingClass = PROGRAM, name = "b", parameters = listOf("Ljava/nio/FloatBuffer;"), returnType = "V"),
        methodCall(definingClass = LUT_EFFECT, name = "c", parameters = listOf("Z"), returnType = "V"),
        methodCall(definingClass = GL, name = "glDrawArrays", parameters = listOf("I", "I", "I"), returnType = "V"),
    ),
)

internal object RemoteDecoderFormat : Fingerprint(
    definingClass = CODEC_CALLBACK,
    name = "onOutputFormatChanged",
    parameters = listOf("Landroid/media/MediaCodec;", MEDIA_FORMAT),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/Object;", name = "getClass", returnType = "Ljava/lang/Class;"),
        methodCall(definingClass = "Ljava/lang/Object;", name = "getClass", returnType = "Ljava/lang/Class;"),
    ),
)

internal object MonitorLutParserBridge : Fingerprint(
    definingClass = MONITOR_LUT,
    name = "parseCube",
    isStatic = true,
    parameters = listOf(CONTEXT, URI),
    returnType = "Ljava/lang/Object;",
)

internal fun controllerSurfaceContainer(className: String) = Fingerprint(
    definingClass = className,
    name = "b",
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        methodCall(definingClass = "Ljava/util/UUID;", name = "toString", returnType = "Ljava/lang/String;"),
        methodCall(
            definingClass = "Lcom/blackmagicdesign/android/blackmagiccam/ui/MainActivity;",
            name = "G",
            parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
            returnType = VIEW,
        ),
        methodCall(definingClass = HOLDER, name = "setFixedSize", parameters = listOf("I", "I"), returnType = "V"),
        methodCall(
            definingClass = "Ler4;",
            name = "J",
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;",
        ),
        methodCall(
            definingClass = "Landroid/view/ViewGroup;",
            name = "addView",
            parameters = listOf("Landroid/view/View;"),
            returnType = "V",
        ),
    ),
)

internal val ControllerSurfaceFactory = controllerSurfaceContainer("Lgz7;")
internal val ControllerSurfaceUpdate = controllerSurfaceContainer("Ljk;")

internal const val UUID_TYPE = "Ljava/util/UUID;"
internal const val FOCUS_POINT = "Landroid/graphics/PointF;"
internal const val REMOTE_CAMERA_MODEL = "Lt8a;"
internal const val REMOTE_CAMERAS = "Lb8a;"
internal const val CAMERA_MODEL = "Luc0;"

internal object RemoteFocusRequest : Fingerprint(
    definingClass = REMOTE_CAMERA_MODEL,
    name = "i1",
    parameters = listOf(FOCUS_POINT),
    returnType = FOCUS_POINT,
    filters = listOf(
        methodCall(definingClass = "Ld61;", name = "getOrientation", returnType = "La61;"),
        methodCall(definingClass = "Ld61;", name = "getIsVerticalVideoEnabled", returnType = "Z"),
        methodCall(definingClass = "Ljga;", name = "a", parameters = listOf(FOCUS_POINT), returnType = FOCUS_POINT),
    ),
)

internal object RemoteFocusResponse : Fingerprint(
    definingClass = REMOTE_CAMERA_MODEL,
    name = "t1",
    parameters = listOf(UUID_TYPE, "Lv91;"),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Lv91;", name = "hasCamPointOfInterest", returnType = "Z"),
        methodCall(definingClass = "Lv91;", name = "getCamPointOfInterest", returnType = "Lh61;"),
        methodCall(definingClass = "Ljga;", name = "b", parameters = listOf(FOCUS_POINT), returnType = FOCUS_POINT),
        methodCall(definingClass = "Lu1a;", name = "E0", parameters = listOf("Lm91;"), returnType = "Lila;"),
        methodCall(definingClass = "Lkla;", name = "<init>", parameters = listOf(FOCUS_POINT, "Z", "Lila;")),
    ),
)

internal object PreviewFocusTap : Fingerprint(
    definingClass = "Ldi1;",
    name = "v",
    parameters = listOf("J", "J"),
    returnType = FOCUS_POINT,
    filters = listOf(
        fieldAccess(definingClass = "Ldi1;", name = "O2", type = "Lplb;", opcode = Opcode.IGET_OBJECT),
        fieldAccess(definingClass = "Ldi1;", name = "O2", type = "Lplb;", opcode = Opcode.IGET_OBJECT),
        fieldAccess(definingClass = "Ldi1;", name = "N2", type = "J", opcode = Opcode.IGET_WIDE),
        methodCall(definingClass = FOCUS_POINT, name = "<init>", parameters = listOf("F", "F")),
    ),
)
