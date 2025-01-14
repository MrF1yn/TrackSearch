package io.sfrei.tracksearch.utils.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@RequiredArgsConstructor(staticName = "of")
public class JsonElement extends JsonUtility {

    @Getter
    private final JsonNode node;

    public static JsonElement empty() {
        return new JsonElement(null);
    }

    public static JsonElement read(final ObjectMapper mapper, final String jsonString) throws JsonProcessingException {
        return new JsonElement(mapper.readTree(jsonString));
    }

    public Stream<JsonElement> elements() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED), false)
                .map(JsonElement::new);
    }

    public JsonElement firstElementFor(final String path) {
        return node.findValues(path).stream().findFirst()
                .map(JsonElement::new).orElse(JsonElement.empty());
    }
    public JsonElement firstElementForWhereNotNested(final String path, final String notPath) {
        if (node == null)
            return this;
        return node.findValues(path).stream()
                .filter(node -> Objects.isNull(node.findValue(notPath)))
                .findFirst()
                .map(JsonElement::new).orElse(JsonElement.empty());
    }

    public boolean isArray() {
        return node.isArray();
    }

    public Stream<JsonElement> arrayElements() {
        if (isArray()) {
            final ArrayNode arrayNode = (ArrayNode) this.node;
            return StreamSupport.stream(arrayNode.spliterator(), false).map(JsonElement::new);
        }
        return Stream.empty();
    }

    public String getAsString(final String path) {
        return getAsString(node, path);
    }

    public String getAsString(final String... paths) {
        for (final String value : paths) {
            String result = getAsString(node, value);
            if (result != null)
                return result;
        }
        return null;
    }

    public Long getLongFor(final String path) {
        return getAsLong(node, path);
    }

    public JsonElement get(final String... route) {
        return new JsonElement(get(node, route));
    }

    public JsonElement getFirstField() {
        return new JsonElement(getFirstField(node));
    }

    public JsonElement getIndex(final int index) {
        return new JsonElement(get(node, index));
    }

    public JsonElement orElseGet(final Supplier<JsonElement> supplier) {
        return node == null ? supplier.get() : this;
    }

    public JsonElement reRead(final ObjectMapper mapper) throws JsonProcessingException {
        return new JsonElement(mapper.readTree(getAsText(node)));
    }

    public <T> T mapToObject(final ObjectMapper mapper, final Class<T> clazz) throws JsonProcessingException {
        return mapper.treeToValue(node, clazz);
    }

    public boolean isNull() {
        return node == null;
    }

    public boolean present() {
        return node != null;
    }

}
