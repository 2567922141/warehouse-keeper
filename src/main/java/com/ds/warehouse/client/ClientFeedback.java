package com.ds.warehouse.client;

/**
 * 抓取「界面刚发出去的那条指令的执行结果」。
 *
 * <p>做法很朴素：界面发指令前先 arm()，之后收到的下一条游戏消息（指令回显）
 * 就当成这次操作的结果。这样连指令回显的文本格式都不用约定。
 */
public final class ClientFeedback {

    /** arm 之后多少毫秒内收到的消息才算这次操作的结果。 */
    private static final long WINDOW_MILLIS = 2500L;
    /** 同一条指令的多行回显，下一行要在这么近的时间内才算同一批。 */
    private static final long CONTINUE_MILLIS = 400L;
    private static final int MAX_LINES = 4;

    private static long armedUntil;
    private static final java.util.List<String> lines = new java.util.ArrayList<>();
    private static String text = "";
    private static long textAt;

    private ClientFeedback() {
    }

    public static void arm() {
        armedUntil = System.currentTimeMillis() + WINDOW_MILLIS;
        lines.clear();
        text = "";
    }

    /** 来自 {@code ClientReceiveMessageEvents.GAME}。 */
    public static void onGameMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > armedUntil) {
            return;
        }
        if (lines.size() < MAX_LINES) {
            lines.add(message);
        }
        armedUntil = now + CONTINUE_MILLIS;
        text = String.join("\n", lines);
        textAt = now;
    }

    public static String text() {
        return text;
    }

    public static long textAt() {
        return textAt;
    }
}
