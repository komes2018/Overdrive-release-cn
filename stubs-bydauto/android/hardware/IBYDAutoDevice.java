package android.hardware;

/**
 * COMPILE-TIME STUB for the platform interface every {@code BYDAuto*Device} implements.
 *
 * <p>Added so {@link android.hardware.bydauto.BYDAutoDeviceManager}'s
 * {@code enableDevice}/{@code disableDevice}/{@code addDevice} signatures can be declared with
 * their real platform parameter type rather than a guessed
 * {@code Object} or {@code int}. Getting the parameter type right matters: the BYD HAL dispatches
 * reflective calls on the EXACT declared type, so a wrapper/`Object` mismatch silently resolves to
 * no method at all — the same class of bug that kept the cluster charge-power read dead when it was
 * invoked with {@code Double.class} instead of {@code Double.TYPE}.
 *
 * <p>Never instantiated by us; we only need the type to exist for reflection and for the stub's
 * signatures to compile.
 */
public interface IBYDAutoDevice {
    int getType();

    boolean onPostEvent(IBYDAutoEvent event);

    boolean postEvent(int i, int i2, double d, Object obj);

    boolean postEvent(int i, int i2, int i3, Object obj);

    boolean postEvent(int i, int i2, byte[] bArr, Object obj);

    void registerListener(IBYDAutoListener listener);

    void unregisterListener(IBYDAutoListener listener);
}
