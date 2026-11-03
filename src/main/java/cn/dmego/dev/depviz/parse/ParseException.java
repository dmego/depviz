package cn.dmego.dev.depviz.parse;

/** 无法解析某一行时抛出，带行号与原文，便于用户直接定位到 tree.txt 的那一行。 */
public final class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final int lineNo;
    public final String rawLine;

    public ParseException(int lineNo, String rawLine, Throwable cause) {
        super("第 " + lineNo + " 行无法解析: " + rawLine
                + (cause != null && cause.getMessage() != null ? "（" + cause.getMessage() + "）" : ""), cause);
        this.lineNo = lineNo;
        this.rawLine = rawLine;
    }
}
