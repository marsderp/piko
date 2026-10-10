/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download.downloadButton

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.misc.download.downloadMediaPatch
import app.crimera.patches.instagram.misc.extension.hooks.instagramInitHook
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.crimera.utils.classNameToExtension
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR = "$DOWNLOAD_DESCRIPTOR/DownloadButtonPatch;"

internal object GetMediaClassNameExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "getMediaClassName",
)

@Suppress("unused")
val downloadButtonPatch =
    bytecodePatch(
        name = "Download button",
        description = "Adds a one tap download button beside share on posts and stories, and below save on reels. " +
            "Albums download every item.",
    ) {
        dependsOn(settingsPatch, decoderEntity, downloadMediaPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            GetMediaClassNameExtensionFingerprint.changeFirstString(classNameToExtension(MEDIA_CLASS_NAME))

            // Register the activity callbacks that place the buttons, right after Application.onCreate's super call.
            instagramInitHook.fingerprint.method.apply {
                val invokeSuperIndex = indexOfFirstInstruction(Opcode.INVOKE_SUPER)
                if (invokeSuperIndex < 0) throw PatchException("InstagramAppShell.onCreate has no invoke-super instruction")
                val contextRegister = getInstruction(invokeSuperIndex).registersUsed.first()

                addInstruction(
                    invokeSuperIndex + 1,
                    "invoke-static {v$contextRegister}, $EXTENSION_CLASS_DESCRIPTOR->init(Landroid/content/Context;)V",
                )
            }

            enableSettings("downloadButton")
        }
    }
