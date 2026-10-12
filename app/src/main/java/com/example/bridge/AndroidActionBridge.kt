package com.example.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.model.ActionResult
import com.example.model.ContactItem
import java.util.Locale

class AndroidActionBridge(private val context: Context) {

    fun openWhatsApp(): ActionResult {
        val packageManager = context.packageManager
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ActionResult.Success("WhatsApp opened successfully")
            }
        }

        // Fallback: try deep link whatsapp://send
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (deepLinkIntent.resolveActivity(packageManager) != null) {
            context.startActivity(deepLinkIntent)
            ActionResult.Success("WhatsApp opened via deep link")
        } else {
            ActionResult.Failure("WhatsApp is not installed on this device.")
        }
    }

    fun openApp(appName: String): ActionResult {
        val cleanName = appName.trim().lowercase(Locale.ROOT)
        if (cleanName.contains("whatsapp")) {
            return openWhatsApp()
        }

        val packageManager = context.packageManager

        // Known standard system/popular app mappings
        when {
            cleanName == "settings" || cleanName.contains("setting") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return ActionResult.Success("Device Settings opened")
            }
            cleanName == "camera" || cleanName.contains("camera") -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    context.startActivity(intent)
                    return ActionResult.Success("Camera opened")
                }
            }
            cleanName == "clock" || cleanName.contains("alarm") -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    context.startActivity(intent)
                    return ActionResult.Success("Clock & Alarms opened")
                }
            }
            cleanName.contains("youtube") -> {
                val pkgIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (pkgIntent != null) {
                    pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(pkgIntent)
                    return ActionResult.Success("YouTube opened")
                } else {
                    return openUrl("https://www.youtube.com")
                }
            }
            cleanName.contains("instagram") -> {
                val pkgIntent = packageManager.getLaunchIntentForPackage("com.instagram.android")
                if (pkgIntent != null) {
                    pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(pkgIntent)
                    return ActionResult.Success("Instagram opened")
                } else {
                    return openUrl("https://www.instagram.com")
                }
            }
            cleanName.contains("chrome") -> {
                val pkgIntent = packageManager.getLaunchIntentForPackage("com.android.chrome")
                if (pkgIntent != null) {
                    pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(pkgIntent)
                    return ActionResult.Success("Chrome opened")
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    return ActionResult.Success("Browser opened")
                }
            }
            cleanName.contains("map") -> {
                val pkgIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                if (pkgIntent != null) {
                    pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(pkgIntent)
                    return ActionResult.Success("Google Maps opened")
                }
            }
        }

        // Generic lookup by installed app label
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
                if (label.contains(cleanName) || cleanName.contains(label)) {
                    val packageName = info.activityInfo.packageName
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return ActionResult.Success("${info.loadLabel(packageManager)} opened")
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through
        }

        return ActionResult.Failure("App '$appName' was not found on this device.")
    }

    fun makeCall(phoneNumber: String, recipientName: String? = null): ActionResult {
        val cleanNumber = phoneNumber.trim().replace(" ", "").replace("-", "")
        if (cleanNumber.isEmpty()) {
            return ActionResult.Failure("Invalid or empty phone number.")
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val displayName = recipientName ?: cleanNumber
        return try {
            if (hasCallPermission) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                ActionResult.Success("Direct call placed to $displayName ($cleanNumber)")
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ActionResult.Success("Opened phone dialer for $displayName ($cleanNumber)")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Failed to initiate call: ${e.localizedMessage}")
        }
    }

    fun callContact(contactName: String): ActionResult {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return ActionResult.Failure("Contacts permission is not granted. Please grant contacts access.")
        }

        val matchingContacts = searchContacts(contactName)

        return when {
            matchingContacts.isEmpty() -> {
                ActionResult.Failure("No contact found matching '$contactName' on your phone.")
            }
            matchingContacts.size == 1 -> {
                val contact = matchingContacts.first()
                makeCall(contact.phoneNumber, contact.name)
            }
            else -> {
                ActionResult.MultipleContacts(contactName, matchingContacts)
            }
        }
    }

    fun searchContacts(query: String): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return list

        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val seenNumbers = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    val id = if (idIndex != -1) cursor.getString(idIndex) ?: "" else ""
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) ?: "" else ""
                    val number = if (numberIndex != -1) cursor.getString(numberIndex) ?: "" else ""

                    val normalizedName = name.lowercase(Locale.ROOT)
                    val normalizedCleanNumber = number.replace(" ", "").replace("-", "")

                    val isMatch = normalizedName.contains(cleanQuery) ||
                        cleanQuery.contains(normalizedName) ||
                        (cleanQuery in listOf("mom", "mummy", "maa", "mother") &&
                            listOf("mom", "mummy", "maa", "mother").any { normalizedName.contains(it) }) ||
                        (cleanQuery in listOf("dad", "papa", "father") &&
                            listOf("dad", "papa", "father").any { normalizedName.contains(it) })

                    if (isMatch && normalizedCleanNumber.isNotEmpty() && !seenNumbers.contains(normalizedCleanNumber)) {
                        seenNumbers.add(normalizedCleanNumber)
                        list.add(ContactItem(id = id, name = name, phoneNumber = number))
                    }
                }
            }
        } catch (e: Exception) {
            // Error reading contacts
        }

        return list
    }

    fun openUrl(url: String): ActionResult {
        val validUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opened $validUrl")
        } catch (e: Exception) {
            ActionResult.Failure("Failed to open URL $validUrl: ${e.localizedMessage}")
        }
    }
}
