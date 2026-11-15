package top.niunaijun.blackbox.fake.service;

import android.os.Build;
import android.os.Process;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class BrowserEngineProxy extends ClassInvocationStub {
    public static final String TAG = "BrowserEngineProxy";

    public BrowserEngineProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    @ProxyMethod("start")
    public static class ProcessStart extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                Object result = method.invoke(who, args);
                
                
                if (args != null && args.length > 0) {
                    Slog.d(TAG, "Process.start() called with args count: " + args.length);
                }
                
                return result;
            } catch (Exception e) {
                Slog.e(TAG, "Process.start() failed: " + e.getMessage(), e);
                
                throw e;
            }
        }
    }

    
    @ProxyMethod("setArgV0")
    public static class SetArgV0 extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                String argV0 = (String) args[0];
                Slog.d(TAG, "Process.setArgV0() called: " + argV0);
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("killProcess")
    public static class KillProcess extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int pid = (args != null && args.length > 0) ? (int) args[0] : -1;

            // Guest apps (e.g. Facebook) sometimes call Process.killProcess(Process.myPid())
            // on themselves after hitting an internal error or detecting a sandboxed/
            // virtualized environment (anti-tamper style self-destruct). Previously this
            // hook only logged the call and then still executed it via method.invoke(),
            // so the guest app could kill the whole virtualized process even for a
            // recoverable background-thread error (e.g. the kotlinx-coroutines
            // ServiceLoader/ClassNotFoundException noise handled by CrashMonitor).
            // Block self-kills here and let BlackBox's own crash/recovery flow decide
            // whether the process should actually die.
            if (pid == Process.myPid()) {
                Slog.w(TAG, "Blocked self Process.killProcess() call for PID: " + pid
                        + " (guest app tried to terminate itself)");
                return null;
            }

            Slog.d(TAG, "Process.killProcess() called for PID: " + pid);
            return method.invoke(who, args);
        }
    }
}
