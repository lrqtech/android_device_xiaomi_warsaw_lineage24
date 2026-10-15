package com.xiaomi.freeform;

import android.app.ActivityOptions;
import android.content.Context;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MiuiFreeformStub {
    private static final String TAG = "WarsawFreeformCompat";
    private static final MiuiFreeformStub INSTANCE = new MiuiFreeformStub();

    private MiuiFreeformStub() {}

    public static MiuiFreeformStub getInstance() { return INSTANCE; }

    public static Object callStaticObjectMethod(Class<?> owner, Class<?> returnType,
            String name, Class<?>[] parameterTypes, Object[] arguments) {
        if (owner == null || name == null) return null;
        try {
            Method method = owner.getDeclaredMethod(name,
                    parameterTypes == null ? new Class<?>[0] : parameterTypes);
            if (!Modifier.isStatic(method.getModifiers())) return null;
            method.setAccessible(true);
            Object result = method.invoke(null, arguments == null ? new Object[0] : arguments);
            return result == null || returnType == null || boxed(returnType).isInstance(result)
                    ? result : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Log.w(TAG, "Static reflection failed for " + owner.getName() + "." + name,
                    exception);
            return null;
        }
    }

    public static Object invoke(Object receiver, String name, Object... arguments) {
        if (receiver == null || name == null) return null;
        Object[] args = arguments == null ? new Object[0] : arguments;
        Class<?> owner = receiver instanceof Class<?> ? (Class<?>) receiver : receiver.getClass();
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                if (!accepts(method.getParameterTypes(), args)) continue;
                try {
                    method.setAccessible(true);
                    Object target = Modifier.isStatic(method.getModifiers()) ? null : receiver;
                    return method.invoke(target, args);
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    Log.w(TAG, "Reflection failed for " + owner.getName() + "." + name,
                            exception);
                    return null;
                }
            }
        }
        Log.w(TAG, "No compatible method " + owner.getName() + "." + name);
        return null;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] == null) {
                if (parameters[i].isPrimitive()) return false;
            } else if (!boxed(parameters[i]).isInstance(arguments[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    public ActivityOptions getActivityOptions(Context context, String packageName,
            boolean fromRecents, boolean fromNotification) {
        return null;
    }

    public boolean supportFreeform() { return false; }
}
