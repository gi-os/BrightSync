# Adding an app to LightSync

Two files' worth of work, then never again.

**1.** Copy `LightSyncBackup.kt` into the app as `backup/LightSyncBackup.kt`, replacing
`PACKAGE` with the app's package.

**2.** Declare what to back up:

```kotlin
package com.gios.lightnotebook.backup

class Backup : LightSyncBackup() {
    override fun contents() = Contents(
        prefs = listOf("lightnotebook"),
        databases = listOf("notebook.db"),
        files = listOf("entries"),
    )
}
```

**3.** Register it. The authority suffix is how LightSync finds the app, so it is not optional:

```xml
<provider
    android:name=".backup.Backup"
    android:authorities="${applicationId}.lightsync.backup"
    android:exported="true" />
```

Exported with no permission attribute is deliberate: the class refuses any caller that is not
LightSync by package *and* signing certificate, which is a check a manifest attribute cannot
express across apps signed with different keys.

## When zipping files is the wrong answer

If the app's stored form cannot be read anywhere else — anything wrapped with an
AndroidKeyStore key, which by design never leaves the device — do not back up the files.
Override `export`/`restore` and emit something portable instead. LightAuth is the worked
example: it exports `otpauth://` URIs, because its database holds secrets encrypted with a key
that dies with the phone. A backup of that database would restore into nothing.
