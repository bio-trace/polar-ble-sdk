# Firmware update with Polar devices

## How to

You can check if there is an update available by using `checkFirmwareUpdate()` method. To update the firmware, simply call `updateFirmware()` method. This will update the device will the latest available firmware.

>[!CAUTION]
>
>Performing firmware update with Polar devices will erase all data inside the device, including [SDK offline recordings](./SdkOfflineRecordingExplained.md). Please make sure to sync any data you wish to retrieve before doing it.

> Note that `doFirstTimeUse()` is not necessary to do after firmware update as there is automatic backup to send user settings back to the device as the last step of the firmware update process.
