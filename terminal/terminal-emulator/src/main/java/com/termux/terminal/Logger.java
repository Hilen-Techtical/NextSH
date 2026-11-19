package com.termux.terminal;

import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {

    public static void logError(TerminalSessionClient client, String logTag, String message) {
        if (client != null)
            client.logError(logTag, message);
        else
            Log.e(logTag, message);
    }

    public static void logWarn(TerminalSessionClient client, String logTag, String message) {
        if (client != null)
            client.logWarn(logTag, message);
        else
            Log.w(logTag, message);
    }

    public static void logInfo(TerminalSessionClient client, String logTag, String message) {
        if (client != null)
            client.logInfo(logTag, message);
        else
            Log.i(logTag, message);
    }

    public static void logDebug(TerminalSessionClient client, String logTag, String message) {
        if (client != null)
            client.logDebug(logTag, message);
        else
            Log.d(logTag, message);
    }

    public static void logVerbose(TerminalSessionClient client, String logTag, String message) {
        if (client != null)
            client.logVerbose(logTag, message);
        else
            Log.v(logTag, message);
    }

    /**
     * Logs {@code message} followed by the simple class name of {@code throwable}, nothing else.
     * The stack trace text is not logged: it prints the exception messages, which can quote what
     * the remote host sent. {@link #getMessageAndStackTraceString} stays for callers that really
     * need the full text.
     */
    public static void logStackTraceWithMessage(TerminalSessionClient client, String tag, String message, Throwable throwable) {
        String className = throwable == null ? null : throwable.getClass().getSimpleName();
        String text;
        if (message == null) text = className;
        else if (className == null) text = message;
        else text = message + " (" + className + ")";
        logError(client, tag, text);
    }

    public static String getMessageAndStackTraceString(String message, Throwable throwable) {
        if (message == null && throwable == null)
            return null;
        else if (message != null && throwable != null)
            return message + ":\n" + getStackTraceString(throwable);
        else if (throwable == null)
            return message;
        else
            return getStackTraceString(throwable);
    }

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return null;

        String stackTraceString = null;

        try {
            StringWriter errors = new StringWriter();
            PrintWriter pw = new PrintWriter(errors);
            throwable.printStackTrace(pw);
            pw.close();
            stackTraceString = errors.toString();
            errors.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return stackTraceString;
    }

}
