package patches.soundcloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element
import patches.shared.uniqueMatch
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Only APIs that the other patches in this repository already use are used here:
// Fingerprint(...).uniqueMatch(), addInstructions, instructions, classDefForEach,
// mutableClassDefBy, document(...).use, get(...), DocumentBuilderFactory for the manifest.

private const val ROOT = "Lcom/soundcloud/android/"
private const val MENU_ITEM = "${ROOT}features/bottomsheet/track/TrackMenuItem;"
private const val INFO_ITEM = "${ROOT}features/bottomsheet/track/TrackMenuItem\$Info;"
private const val SLEEP_ITEM = "Lnl/johninnl/soundcloud/SleepTimerMenuItem;"
private const val ENTRY = "Lnl/johninnl/soundcloud/SleepTimerEntry;"
private const val OBJECT = "Ljava/lang/Object;"
private const val COLLECTION = "Ljava/util/Collection;"
private const val KOTLIN_COLLECTIONS = "Lkotlin/collections/CollectionsKt;"
private const val STRING_NAME = "morphe_sleep_timer"

/** Launcher Activity (dotted class name), filled in by [sleepTimerResourcePatch]. */
private var launcherActivity: String? = null

context(context: BytecodePatchContext)
private fun exact(owner: String, name: String, result: String, vararg parameters: String) =
    Fingerprint(definingClass = "$ROOT$owner;", name = name, returnType = result,
        parameters = parameters.toList()).uniqueMatch().method

private fun launcherActivityOf(manifestFile: File): String? {
    val ns = "http://schemas.android.com/apk/res/android"
    val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(manifestFile)
    val pkg = manifest.documentElement.getAttribute("package")

    fun qualify(name: String): String? = when {
        name.isEmpty() -> null
        name.startsWith(".") -> pkg + name
        '.' !in name -> "$pkg.$name"
        else -> name
    }

    fun isLauncher(element: Element): Boolean {
        val filters = element.getElementsByTagName("intent-filter")
        for (f in 0 until filters.length) {
            val filter = filters.item(f) as? Element ?: continue
            fun declares(tag: String, value: String): Boolean {
                val nodes = filter.getElementsByTagName(tag)
                for (n in 0 until nodes.length) {
                    val node = nodes.item(n) as? Element ?: continue
                    if (node.getAttributeNS(ns, "name") == value) return true
                }
                return false
            }
            if (declares("action", "android.intent.action.MAIN") &&
                declares("category", "android.intent.category.LAUNCHER")) return true
        }
        return false
    }

    val activities = manifest.getElementsByTagName("activity")
    for (i in 0 until activities.length) {
        val element = activities.item(i) as? Element ?: continue
        if (isLauncher(element)) qualify(element.getAttributeNS(ns, "name"))?.let { return it }
    }
    // An alias name is not a class; its targetActivity is.
    val aliases = manifest.getElementsByTagName("activity-alias")
    for (i in 0 until aliases.length) {
        val element = aliases.item(i) as? Element ?: continue
        if (isLauncher(element)) qualify(element.getAttributeNS(ns, "targetActivity"))?.let { return it }
    }
    return null
}

/**
 * Adds the menu item's English title to res/values/strings.xml and finds the launcher
 * Activity. Unnamed, so it is a dependency only (not listed as a user-selectable patch).
 * The Russian language patch depends on it too: it drops translated keys that are missing
 * from the base strings.xml, so this string must exist before that filter runs.
 */
internal val sleepTimerResourcePatch = resourcePatch {
    dependsOn(originalInput)
    execute {
        if (!get("res/values/strings.xml").isFile)
            throw PatchException("SoundCloud res/values/strings.xml not found")
        document("res/values/strings.xml").use { doc ->
            val strings = doc.getElementsByTagName("string")
            var present = false
            for (i in 0 until strings.length) {
                val element = strings.item(i) as? Element ?: continue
                if (element.getAttribute("name") == STRING_NAME) { present = true; break }
            }
            if (!present) {
                val element = doc.createElement("string")
                element.setAttribute("name", STRING_NAME)
                element.textContent = "Sleep timer"
                doc.documentElement.appendChild(element)
            }
        }
        launcherActivity = launcherActivityOf(get("AndroidManifest.xml"))
    }
}

@Suppress("unused")
val sleepTimerMenuPatch = bytecodePatch(
    name = "Sleep timer in track menu",
    description = "Adds a \"Таймер сна\" item to the track menu. It opens a dialog with 15, 30, 45 and " +
        "60 minute options and pauses playback when the time is up.",
    default = true,
) {
    compatibleWith(soundcloud)
    dependsOn(originalInput, sleepTimerResourcePatch)
    extendWith("extensions/soundcloud.mpe")

    execute {
        // 1. Remember the host Activity: AlertDialog needs an Activity window token.
        val launcher = launcherActivity
            ?: throw PatchException("SoundCloud launcher activity not found in AndroidManifest.xml")
        Fingerprint(
            // Smali descriptors use '/', the manifest uses '.'.
            definingClass = "L${launcher.replace('.', '/')};",
            name = "onCreate",
            returnType = "V",
            parameters = listOf("Landroid/os/Bundle;"),
        ).uniqueMatch().method.addInstructions(
            0,
            "invoke-static {p0}, $ENTRY->attach(Landroid/app/Activity;)V",
        )

        // 2. Create the item. TrackBottomSheetDataMapper$from$1$3.a() builds the menu list:
        //      invoke-static {vDivider, vSrc}, CollectionsKt->j0(Object, Collection)ArrayList
        //      move-result-object v0                  <- the list
        //      new-instance v2, TrackMenuItem$Info     <- insert right before this
        //      ...
        //      invoke-static {v2, v0}, CollectionsKt->j0(...) ; move-result-object v0
        //    (checked against the smali of SoundCloud 2026.11.16-release). v2 is the destination of
        //    the new-instance below, so it is free here; v0 is the list. No branch targets the
        //    new-instance instruction, so a plain insertion is seen on every path.
        val mapper = exact("features/bottomsheet/track/TrackBottomSheetDataMapper\$from\$1\$3",
            "a", OBJECT, OBJECT, OBJECT, OBJECT, OBJECT, OBJECT)
        val list = mapper.instructions.toList()
        val infoIndex = list.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.NEW_INSTANCE &&
                ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == INFO_ITEM
        }.map { it.index }.singleOrNull()
            ?: throw PatchException("SoundCloud track menu Info item creation changed")
        if (infoIndex < 2) throw PatchException("SoundCloud track menu Info item is at the method start")

        val moveResult = list[infoIndex - 1]
        val addCall = list[infoIndex - 2]
        val addRef = (addCall as? ReferenceInstruction)?.reference as? MethodReference
        if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT || addCall.opcode != Opcode.INVOKE_STATIC ||
            addRef == null || addRef.definingClass != KOTLIN_COLLECTIONS ||
            addRef.parameterTypes.map { it.toString() } != listOf(OBJECT, COLLECTION))
            throw PatchException("SoundCloud track menu list 'add item' call changed")

        val listRegister = (moveResult as OneRegisterInstruction).registerA
        val freeRegister = (list[infoIndex] as OneRegisterInstruction).registerA
        if (listRegister > 15 || freeRegister > 15)
            throw PatchException("SoundCloud track menu registers are out of 4-bit range")

        // Same method SoundCloud itself uses to add an item: copied, not hard-coded, because
        // its obfuscated name changes between builds. Parameters are (element, list).
        val addSmali = "${addRef.definingClass}->${addRef.name}" +
            "(${addRef.parameterTypes.joinToString("")})${addRef.returnType}"
        mapper.addInstructions(
            infoIndex,
            """
                new-instance v$freeRegister, $SLEEP_ITEM
                invoke-direct {v$freeRegister}, $SLEEP_ITEM-><init>()V
                invoke-static {v$freeRegister, v$listRegister}, $addSmali
                move-result-object v$listRegister
            """,
        )

        // 3. Click handling. The click dispatcher (BottomSheetActionHandler) was not among the
        //    provided smali, so it is found structurally: a method that takes the clicked item
        //    as TrackMenuItem (or a single Object, for a lambda) and tests it with instance-of
        //    against at least ten TrackMenuItem subclasses. Exactly one such method must exist;
        //    otherwise patching stops with the candidates listed instead of guessing.
        val subclassPrefix = MENU_ITEM.removeSuffix(";") + "$"
        fun testedSubclasses(method: Method) = method.implementation?.instructions?.toList().orEmpty()
            .filter { it.opcode == Opcode.INSTANCE_OF }
            .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
            .filter { it.startsWith(subclassPrefix) }
            .distinct()

        data class Candidate(val method: Method, val tested: Int)

val candidates = mutableListOf<Candidate>()
val nearMisses = mutableListOf<Candidate>()
val debugMethods = mutableListOf<String>()

classDefForEach { klass ->
    for (method in klass.methods) {
        val takesItem = method.parameterTypes.any {
            it.toString() == MENU_ITEM
        }
        val objectLambda = method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString() == OBJECT

        val testedTypes = testedSubclasses(method)
        val tested = testedTypes.size

        if (takesItem || objectLambda) {
            if (tested >= 10) candidates += Candidate(method, tested)
            else if (tested >= 5) nearMisses += Candidate(method, tested)
        }

        if (tested >= 3 && debugMethods.size < 40) {
            debugMethods +=
                "${method.definingClass}->${method.name} " +
                "params=[${method.parameterTypes.joinToString()}] " +
                "tested=$tested types=[${testedTypes.joinToString()}]"
        }
    }
}

fun describe(items: List<Candidate>) =
    items.joinToString { "${it.method.definingClass}->${it.method.name} (${it.tested})" }

if (candidates.isEmpty()) {
    throw PatchException(
        "SoundCloud menu click dispatcher not found; " +
        "near misses: ${describe(nearMisses)}\n" +
        "SLEEP DEBUG:\n" +
        debugMethods.joinToString("\n").ifBlank { "<none>" }
    )
}
        if (candidates.size > 1)
            throw PatchException("Ambiguous SoundCloud menu click dispatchers: ${describe(candidates)}")

        val dispatch = candidates.single().method
        val handler = mutableClassDefBy(dispatch.definingClass).methods.single {
            it.name == dispatch.name && it.parameterTypes == dispatch.parameterTypes && it.returnType == dispatch.returnType
        }

        // p-register of the clicked item: skip 'this', then every earlier parameter (wide = 2).
        fun width(type: CharSequence) = if (type.toString() == "J" || type.toString() == "D") 2 else 1
        val isStatic = AccessFlags.STATIC.isSet(handler.accessFlags)
        val itemParam = handler.parameterTypes.indexOfFirst { it.toString() == MENU_ITEM }.let { if (it >= 0) it else 0 }
        var itemP = if (isStatic) 0 else 1
        for (i in 0 until itemParam) itemP += width(handler.parameterTypes[i])
        val parameterRegisters = (if (isStatic) 0 else 1) + handler.parameterTypes.sumOf { width(it) }

        // v0 is scratch at method entry, so a local must exist (a parameter must not be clobbered).
        if (handler.implementation!!.registerCount - parameterRegisters < 1)
            throw PatchException("SoundCloud menu click dispatcher has no free local register")

        val returnSmali = when {
            handler.returnType == "V" -> "return-void"
            handler.returnType.startsWith("L") || handler.returnType.startsWith("[") ->
                "const/4 v0, 0x0\nreturn-object v0"
            else -> throw PatchException("SoundCloud menu click dispatcher returns ${handler.returnType}")
        }

        // move-object/from16 copes with a parameter register above v15; instance-of needs 4 bits.
        // Same label pattern as the "Disable analytics" patch: the label points at the method's
        // original first instruction, evaluated before the insertion.
        handler.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p$itemP
                instance-of v0, v0, $SLEEP_ITEM
                if-eqz v0, :morphe_not_sleep_timer
                invoke-static {}, $ENTRY->open()V
                $returnSmali
            """,
            ExternalLabel("morphe_not_sleep_timer", handler.getInstruction(0)),
        )
    }
}
