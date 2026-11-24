# Second Brain Android

Second Brain stores your workspace on the device in Room. Local notes, tasks, events, collections, and reminders remain available offline.

## 3.0

- Home starts with global search, pinned shortcuts, and folders that expand in place. Settings → Appearance → Home layout switches to the original folder carousel. Both layouts use the same workspace.
- Hold Plus to record and release to save. The microphone widget and compact Voice Memo shortcut are also available. A bundled multilingual Whisper model transcribes saved recordings on the phone. Your audio and transcript remain available if organization needs a retry.
- Open Settings → AI Intelligence → Continue with ChatGPT and grant plan use with an eligible account. The app uses the official OpenAI authorization and streamed text inference endpoints. It sends the transcript and relevant workspace details to organize items. Turn off credit use in **Manage usage** to keep usage within your ChatGPT plan allowance. Subscription mode requires no API key.
- To organize with Claude instead, open Settings → AI Intelligence → Claude, paste your Anthropic API key, and choose Opus 5.5, Sonnet 5.5 or Haiku 5.5. Requests are billed to your Claude Console account. While Claude is on, it organizes captures and ChatGPT settings are not used for organization. The voice conversation assistant still uses OpenAI.
- The app's public build contains no workspace data or credentials. ChatGPT account credentials are encrypted by Android Keystore and stored outside device backups.

## V0.4.8.2

- Brain Dump automatically saves clear requests and keeps the original capture available. Ambiguous proposals stay editable in Drafts. Task confirmations show the saved schedule, reminder and priority.
- Collections uses the bottom creation button and contextual folder actions. Drag to reorder folders, long-press items to select several, and use Appearance settings to show subfolder indicators.
- Appearance includes Modern, Soft, Handwritten and Elegant typography. Soft uses Nunito. Handwritten uses Kalam for headings. Both fonts include their SIL Open Font License in the app.
- Quick Calendar keeps its frame in place across Week, Upcoming and Month. Notes, tasks and events share formatting, text colors and list controls in their editors.

## V0.4.8.1 Collections update

- Folders and subfolders share one compact carousel with a Back control. Folder order and title visibility remain available at each level.
- Notes, tasks, and events use content-sized cards with compact scheduling details and more room for previews.

## V0.4.8

- Brain Dump and voice memos use the Android planner. It fills in task dates, times, and reminders, and retains hard deadlines separately from scheduled work. The slime accompanies voice capture while live conversation is paused.
- Quick Calendar has a standalone launcher entry, a launcher shortcut, and a home-screen widget button. Calendar opens in Upcoming, with Week and Month on either side.
- Existing items open in a read view. Select **Edit** to change an item, then **Save** or **Cancel**. Folder order and title visibility are saved on the device.
- A local workspace server provides authenticated MCP tools and a revision-checked sync API. See [Backend setup](backend/README.md).

### Galaxy Side Button

Open **Settings → Advanced features → Side button → Double press**. Choose the **Quick Calendar** app entry if your One UI picker lists it. If Second Brain has a gear for in-app actions, open it and select **Quick Calendar** if offered. The two-button widget and Android launcher shortcut provide the same calendar entry point. Exact picker listings vary by device and software version. [Samsung Side Button instructions](https://www.samsung.com/uk/support/mobile-devices/how-to-customise-the-side-button-with-new-features-on-your-galaxy-phone-and-tablet/)

If **Long press** or **Good Lock → RegiStar → Side-Key press and hold action** offers **Voice Memo**, select it. The shortcut opens Second Brain’s compact panel and begins recording after microphone access is granted. Tap **Finish recording** to save and organize the memo. Android reserves the power-key release for the system on personal devices. The home-screen microphone widget also begins capture on tap. [Samsung Side Button instructions](https://www.samsung.com/uk/support/mobile-devices/how-to-customise-the-side-button-with-new-features-on-your-galaxy-phone-and-tablet/) · [Android key handling](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/policy/PhoneWindowManager.java)

### Data upgrades

Room migration 8 → 9 adds nullable columns for task duration, recurrence patterns, and inferred scheduling fields. Existing data and IDs are retained. Older backups can omit the new fields. A space can contain folders and subfolders. Existing deeper folders remain accessible and can be moved toward the root.

### Build and test

Use the Android Studio JDK with the SDK platform specified by the Gradle project. From the repository root on Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
```

The release APK is unsigned unless the `SB_RELEASE_*` signing settings are supplied. Public APKs and checksums are available from the distribution repository.

Second Brain is distributed under the MIT license. The speech dependencies and bundled fonts use their own licenses, included under `app/src/main/assets/licenses`.

[Download publicly released APKs](https://github.com/LeoU29935/second-brain-releases/releases).
