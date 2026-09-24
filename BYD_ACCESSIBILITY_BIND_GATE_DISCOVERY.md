# BYD Start Gate Blocks Accessibility Service Binds (DiLink 3.0, Android 10)

## Summary

On BYD DiLink 3.0 the OEM's application start gate refuses the system's attempt to bind a
third-party `AccessibilityService` when that app's process is not already running. The refusal is
silent. `AccessibilityManagerService` is left holding a pending bind that never completes, the
service stays in `Binding services`, `onServiceConnected()` never runs, and nothing retries.

The rule this produces:

> An accessibility service can only bind while its own app process is already running.

OverDrive already works around this. The code records the symptoms accurately but not the cause.

## The gate

From `/system/framework/services.jar`, `ActivityManagerService`:

```java
boolean isTargetAppEnabledStartedBy3rd(ServiceRecord serviceRecord) {
    Integer num = getAppOpsDataMapInternal().get(String.valueOf(serviceRecord.appInfo.uid));
    return (num == null || num.intValue() != 1) || isAppRunning(serviceRecord.appInfo.uid);
}
```

The start is permitted when the uid is not marked blocked, or the app is already running. It is
skipped only when the uid is blocked and the process is dead. `mapAppOpsData[uid]` is set to `1` on
every `PACKAGE_ADDED` and `PACKAGE_REPLACED`, so each install or update re-blocks every third-party
app.

This predicate is consulted from `ActiveServices.bindServiceLocked`, not only from activity and
provider starts. A bind requested by the system for `AccessibilityManagerService` is therefore
subject to it. The gate applies to callers at `uid >= 10000`. A caller below that, such as shell at
uid 2000 or the app itself, is exempt.

## Evidence

BYD Seal, DiLink 3.0, Android 10. Caller uid 1000 is the system:

```text
D/ssc_skip(681): bindServiceLocked  1000  want to bind 10085 package no.stink.keepalive ignored !!!
```

That package was listed in `enabled_accessibility_services` and had never appeared in
`Bound services`. A second third-party accessibility service on the same unit was in the identical
state.

## What this explains in OverDrive

`KeepAliveAccessibilityService` binds reliably because the daemon keeps the app process alive, which
satisfies `isAppRunning(uid)`.

The AMS binding wedge the keymap watchdog recovers from is this gate catching OverDrive in a window
where its process is not up.

`forceRestartAppForA11y()` works for a simpler reason than clearing a stale `ServiceRecord`: the
relaunch makes the process exist, which is the gate's exemption.

The existing note that a toggle of the Secure setting does not un-wedge it is correct. A toggle does
not create a process.

## Recovery sequence

The order matters. Each step does something the others do not:

1. `am force-stop <pkg>`. Tears down the stale pending binding. While it is present, later attempts
   are ignored. This also drops the component from `enabled_accessibility_services`, which is
   standard AOSP behaviour for a stopped package.
2. Start the process, with `am start` on any activity or any same-uid caller. This is the gate's
   exemption.
3. Re-add the component to `enabled_accessibility_services`. The bind now reaches a live process and
   completes, in 6 to 9 seconds on this unit.

Step 3 before step 2, or step 2 without step 1, leaves the service wedged.

## Implications

Clearing the auto-start block in `com.byd.appstartmanagement` is necessary but not sufficient. The
block returns on every install, and the gate is consulted on every bind.

For any app whose accessibility service must survive a process death, something exempt from the gate
has to start it: a shell-uid helper, or a supervisor that keeps the process alive. There is no
in-app fix, because the fix requires the process to already exist.
