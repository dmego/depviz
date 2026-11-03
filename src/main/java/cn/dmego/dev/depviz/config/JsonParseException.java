package cn.dmego.dev.depviz.config;

/** JSON 文本不合法。 */
public class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonParseException(String message) {
        super(message);
    }
}
