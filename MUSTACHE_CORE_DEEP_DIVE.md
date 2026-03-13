# Mustache Infrastructure in OpenSearch Core - Deep Dive

**Base Path:** `/Users/rithinp/Documents/OS/core/OpenSearch/modules/lang-mustache/`

---

## 1. Dependencies

**File:** `build.gradle`

```
dependencies {
  api "com.github.spullara.mustache.java:compiler:0.9.14"
}
```

The sole external dependency is the **mustache.java** library version 0.9.14, from `com.github.spullara.mustache.java:compiler`.

The plugin classname is `org.opensearch.script.mustache.MustachePlugin` and it has `hasClientJar = true` (for template APIs and query).

REST API specs included: `put_script`, `render_search_template`, `search_template`, `msearch_template`, `lang_mustache`.

---

## 2. Plugin Registration: MustachePlugin

**File:** `src/main/java/org/opensearch/script/mustache/MustachePlugin.java`

```java
public class MustachePlugin extends Plugin implements ScriptPlugin, ActionPlugin, SearchPlugin
```

### What it registers:

1. **ScriptEngine**: `MustacheScriptEngine` via `getScriptEngine()`
2. **Three transport actions:**
   - `SearchTemplateAction` -> `TransportSearchTemplateAction`
   - `RenderSearchTemplateAction` -> `TransportRenderSearchTemplateAction`
   - `MultiSearchTemplateAction` -> `TransportMultiSearchTemplateAction`
3. **Three REST handlers:**
   - `RestSearchTemplateAction` (routes: `/_search/template`, `/{index}/_search/template`)
   - `RestMultiSearchTemplateAction` (routes: `/_msearch/template`, `/{index}/_msearch/template`)
   - `RestRenderSearchTemplateAction` (routes: `/_render/template`, `/_render/template/{id}`)

---

## 3. The Script Engine: MustacheScriptEngine

**File:** `src/main/java/org/opensearch/script/mustache/MustacheScriptEngine.java`

```java
public final class MustacheScriptEngine implements ScriptEngine
```

### Key Constants
- `NAME = "mustache"` -- the script language identifier.

### The compile() Method (THE CRITICAL PATH)

```java
public <T> T compile(String templateName, String templateSource, ScriptContext<T> context, Map<String, String> options)
```

1. **Validates** that `context.instanceClazz` equals `TemplateScript.class`. Only the `TemplateScript.CONTEXT` is supported.
2. **Creates a `CustomMustacheFactory`** based on MIME type from `options.get(Script.CONTENT_TYPE_OPTION)`. Defaults to JSON if no option is set.
3. **Compiles** via `factory.compile(reader, "query-template")` which returns a `com.github.mustachejava.Mustache` object.
4. **Wraps** in a lambda: `TemplateScript.Factory compiled = params -> new MustacheExecutableScript(template, params)`.
5. **Returns** `context.factoryClazz.cast(compiled)`, which is a `TemplateScript.Factory`.

### The MustacheExecutableScript (PRIVATE INNER CLASS)

```java
private class MustacheExecutableScript extends TemplateScript
```

- Constructor takes `(Mustache template, Map<String, Object> params)`.
- `execute()`:
  1. Creates a `StringWriter`.
  2. Calls `template.execute(writer, params)` inside `AccessController.doPrivileged()`.
  3. Returns `writer.toString()`.

### Connection to ScriptService

`MustacheScriptEngine.compile()` is called by `ScriptService.compile()` (see below). The result is a `TemplateScript.Factory` which produces `TemplateScript` instances that render the template with given params.

---

## 4. The Factory: CustomMustacheFactory

**File:** `src/main/java/org/opensearch/script/mustache/CustomMustacheFactory.java`

```java
public class CustomMustacheFactory extends DefaultMustacheFactory
```

This is the heart of template compilation. It extends `com.github.mustachejava.DefaultMustacheFactory`.

### Constructor

```java
public CustomMustacheFactory(String mimeType)
public CustomMustacheFactory() // defaults to JSON_MIME_TYPE
```

- Sets the `ObjectHandler` to `CustomReflectionObjectHandler`.
- Creates an encoder based on MIME type.

### Encoders (MIME-type based)

| MIME Type | Encoder Class | Behavior |
|-----------|--------------|----------|
| `application/json; charset=UTF-8` | `JsonEscapeEncoder` | JSON-escapes strings via Jackson's `JsonStringEncoder.quoteAsString()` |
| `application/json` | `JsonEscapeEncoder` | Same as above |
| `text/plain` | `DefaultEncoder` | No encoding, passthrough |
| `application/x-www-form-urlencoded` | `UrlEncoder` | URL-encodes via `URLEncoder.encode(s, UTF-8)` |

The `encode()` method is overridden to delegate to the selected encoder. This is called by the mustache library whenever a `{{variable}}` is rendered (double-mustache variables are HTML-escaped by default in mustache; this override replaces that with context-appropriate encoding).

### Encoder Interface

```java
@FunctionalInterface
interface Encoder {
    void encode(String s, Writer writer) throws IOException;
}
```

### createMustacheVisitor() -- THE CUSTOM AST HOOK

```java
@Override
public MustacheVisitor createMustacheVisitor() {
    return new CustomMustacheVisitor(this);
}
```

This is how OpenSearch injects custom Mustache behavior. The `DefaultMustacheFactory.compile()` method internally creates a visitor that parses the template into an AST of `Code` objects. By overriding `createMustacheVisitor()`, OpenSearch intercepts the `iterable()` callback to detect custom function tags.

---

### CustomMustacheVisitor (INNER CLASS)

```java
class CustomMustacheVisitor extends DefaultMustacheVisitor
```

Overrides one method:

```java
@Override
public void iterable(TemplateContext templateContext, String variable, Mustache mustache)
```

This is called when the parser encounters a section tag `{{#variable}}...{{/variable}}`. The visitor checks the variable name against the custom function registry:

| Check | Code Subclass Created |
|-------|----------------------|
| `ToJsonCode.match(variable)` (case-insensitive "toJson") | `ToJsonCode` |
| `JoinerCode.match(variable)` (case-insensitive "join") | `JoinerCode` |
| `CustomJoinerCode.match(variable)` (matches `join delimiter='...'`) | `CustomJoinerCode` |
| `UrlEncoderCode.match(variable)` (case-insensitive "url") | `UrlEncoderCode` |
| None of the above | Standard `IterableCode` |

---

### CustomCode (ABSTRACT INNER CLASS) -- KEY FOR VARIABLE EXTRACTION

```java
abstract static class CustomCode extends IterableCode
```

This is the base class for `ToJsonCode`, `JoinerCode`, and `CustomJoinerCode`.

#### Constructor
```java
CustomCode(TemplateContext tc, DefaultMustacheFactory df, Mustache mustache, String code)
```
Calls `super(tc, df, mustache, extractVariableName(code, mustache, tc))` -- it extracts the variable name from the inner content at compile time.

#### extractVariableName() -- THE CRITICAL METHOD FOR PARAMETER EXTRACTION

```java
protected static String extractVariableName(String fn, Mustache mustache, TemplateContext tc)
```

**How it works:**

1. Gets `Code[] codes = mustache.getCodes()` -- these are the compiled codes inside the section body.
2. **Validates** there is exactly one code element; throws `MustacheException` otherwise ("must contain one and only one identifier").
3. If `codes[0] instanceof WriteCode` -- the inner content is plain text (e.g., `{{#toJson}}variableName{{/toJson}}`). It executes the WriteCode to capture the text and returns it as the variable name.
4. Otherwise -- the inner content is a Mustache expression (e.g., `{{#toJson}}{{someVar}}{{/toJson}}`). It calls `codes[0].identity(capture)` to get the identity string of the code, which is the Mustache tag itself (e.g., `{{someVar}}`). **But this path returns the raw tag, not the variable name.** However, the "one and only one" restriction means only a single Code is allowed.

**Important:** This is the only place in OpenSearch that does compile-time AST inspection of a Mustache template. It uses `mustache.getCodes()` to walk the code tree, and checks `instanceof WriteCode` to distinguish literal text from variable references.

#### execute() Override

```java
@Override
public Writer execute(Writer writer, final List<Object> scopes) {
    Object resolved = get(scopes);
    writer = handle(writer, createFunction(resolved), scopes);
    appendText(writer);
    return writer;
}
```

At runtime, `get(scopes)` resolves the variable name extracted at compile time from the scope chain, then `createFunction(resolved)` transforms it.

---

### ToJsonCode

```java
static class ToJsonCode extends CustomCode
```

- **Match:** `CODE.equalsIgnoreCase(variable)` where `CODE = "toJson"`
- **Behavior:** Converts `Iterable` to JSON array, `Map` to JSON object, or calls `oh.stringify()` for primitives.
- Uses `XContentBuilder` with `MediaTypeRegistry.JSON`.

### JoinerCode

```java
static class JoinerCode extends CustomCode
```

- **Match:** `CODE.equalsIgnoreCase(variable)` where `CODE = "join"`
- **Default delimiter:** `","`
- **Behavior:** Joins `Iterable` elements with the delimiter using `StringJoiner`.

### CustomJoinerCode

```java
static class CustomJoinerCode extends JoinerCode
```

- **Match:** Regex `^(?:join delimiter='(.*)')$`
- **Extracts delimiter** from the section tag variable name.
- Example: `{{#join delimiter=' and '}}array{{/join delimiter=' and '}}`

### UrlEncoderCode

```java
static class UrlEncoderCode extends DefaultMustache
```

**Note:** This extends `DefaultMustache`, NOT `CustomCode`/`IterableCode`. It is fundamentally different.

- **Match:** `CODE.equalsIgnoreCase(variable)` where `CODE = "url"`
- **Constructor:** `super(tc, df, mustache.getCodes(), variable)` -- takes the inner codes directly.
- **run():** Iterates over inner codes, executes each to a capture writer, then URL-encodes the result.
- This means `{{#url}}...{{/url}}` can contain arbitrary Mustache content (multiple codes), unlike `toJson`/`join` which require exactly one identifier.

---

## 5. CustomReflectionObjectHandler

**File:** `src/main/java/org/opensearch/script/mustache/CustomReflectionObjectHandler.java`

```java
final class CustomReflectionObjectHandler extends ReflectionObjectHandler
```

Extends `com.github.mustachejava.reflect.ReflectionObjectHandler`.

### coerce() Override

Makes arrays and collections work as maps for Mustache traversal:

- **Arrays** -> `ArrayMap`: wraps Java arrays as `AbstractMap<Object, Object> implements Iterable<Object>`. Supports numeric index access (`data.0`, `data.1`) and `.size`.
- **Collections** -> `CollectionMap`: wraps `Collection<Object>` similarly. Supports numeric index access and `.size`.

### stringify() Override

```java
public String stringify(Object object) {
    CollectionUtils.ensureNoSelfReferences(object, "CustomReflectionObjectHandler stringify");
    return super.stringify(object);
}
```

Prevents infinite recursion from self-referencing collections.

### ArrayMap (INNER CLASS)
- `get(Object key)`: Returns element by integer index, or `.size`.
- `entrySet()`: Returns entries with integer keys.
- `iterator()`: Iterates over array elements.

### CollectionMap (INNER CLASS)
- Same interface as ArrayMap but wraps `Collection<Object>`.
- Uses `Iterables.get(col, index)` for indexed access.

---

## 6. TemplateScript (Server Core)

**File:** `/Users/rithinp/Documents/OS/core/OpenSearch/server/src/main/java/org/opensearch/script/TemplateScript.java`

```java
public abstract class TemplateScript
```

### Key Members

- `Map<String, Object> params` -- constructor parameter.
- `getParams()` -- returns params.
- `PARAMETERS = {}` -- empty, no special parameters.
- `abstract String execute()` -- the main execution method.

### Factory Interface

```java
public interface Factory {
    TemplateScript newInstance(Map<String, Object> params);
}
```

### Script Context

```java
public static final ScriptContext<Factory> CONTEXT = new ScriptContext<>("template", Factory.class);
```

The context name is `"template"`.

---

## 7. ScriptService.compile() (Server Core)

**File:** `/Users/rithinp/Documents/OS/core/OpenSearch/server/src/main/java/org/opensearch/script/ScriptService.java`

```java
public <FactoryType> FactoryType compile(Script script, ScriptContext<FactoryType> context)
```

### Compilation Flow

1. **Extracts** `type`, `lang`, `idOrCode`, `options` from the `Script` object.
2. **If STORED:** Fetches source from cluster state via `getScriptFromClusterState(id)`, overriding `lang`, `idOrCode`, and `options`.
3. **Gets engine:** `getEngine(lang)` -- looks up `ScriptEngine` by language name (e.g., "mustache").
4. **Validates:** type is enabled, context exists and is enabled, inline size limits.
5. **Delegates to cache:** `scriptCache.compile(context, scriptEngine, id, idOrCode, type, options)` which calls `scriptEngine.compile(id, idOrCode, context, options)`.

### Important for ml-commons

When ml-commons needs to compile a search template, the call chain is:
```
ScriptService.compile(script, TemplateScript.CONTEXT)
  -> ScriptCache.compile(...)
    -> MustacheScriptEngine.compile(templateName, templateSource, TemplateScript.CONTEXT, options)
      -> CustomMustacheFactory.compile(reader, "query-template")
        -> returns com.github.mustachejava.Mustache
      -> wraps in TemplateScript.Factory lambda
```

---

## 8. ScriptEngine Interface (Server Core)

**File:** `/Users/rithinp/Documents/OS/core/OpenSearch/server/src/main/java/org/opensearch/script/ScriptEngine.java`

```java
@PublicApi(since = "1.0.0")
public interface ScriptEngine extends Closeable {
    String getType();
    <FactoryType> FactoryType compile(String name, String code, ScriptContext<FactoryType> context, Map<String, String> params);
    Set<ScriptContext<?>> getSupportedContexts();
}
```

---

## 9. Transport Actions (Execution Chain)

### TransportSearchTemplateAction

**File:** `src/main/java/org/opensearch/script/mustache/TransportSearchTemplateAction.java`

The central `convert()` static method (also used by multi-search):

```java
static SearchRequest convert(
    SearchTemplateRequest searchTemplateRequest,
    SearchTemplateResponse response,
    ScriptService scriptService,
    NamedXContentRegistry xContentRegistry
) throws IOException
```

**Flow:**
1. Creates a `Script` object from the `SearchTemplateRequest` (type, lang="mustache", source, params).
2. `scriptService.compile(script, TemplateScript.CONTEXT).newInstance(script.getParams())` -- compiles and creates instance.
3. `compiledScript.execute()` -- renders template with params to produce a JSON source string.
4. Sets rendered source on response: `response.setSource(new BytesArray(source))`.
5. If `simulate` is true, returns null (no actual search).
6. Otherwise, parses the rendered JSON into a `SearchSourceBuilder` and sets it on the `SearchRequest`.

### TransportRenderSearchTemplateAction

Extends `TransportSearchTemplateAction`, just changes the action name.

### TransportMultiSearchTemplateAction

Loops over requests, calls `convert()` for each, builds a `MultiSearchRequest`, executes it, maps responses back.

---

## 10. Request/Response Classes

### SearchTemplateRequest

- `SearchRequest request` -- the underlying search request.
- `boolean simulate` -- if true, only render, don't search.
- `boolean explain`, `boolean profile` -- search options.
- `ScriptType scriptType` -- INLINE or STORED.
- `String script` -- template source or stored script ID.
- `Map<String, Object> scriptParams` -- template parameters.
- Parses from XContent: supports `"id"` (stored), `"source"/"inline"/"template"` (inline), `"params"`, `"explain"`, `"profile"`.

### SearchTemplateResponse

- `BytesReference source` -- rendered template output.
- `SearchResponse response` -- search results (null if simulate).

### MultiSearchTemplateRequest / MultiSearchTemplateResponse

Standard multi-request wrappers.

---

## 11. REST Handlers

### RestSearchTemplateAction
- Routes: `GET/POST /_search/template`, `GET/POST /{index}/_search/template`
- Parses `SearchRequest` params, then `SearchTemplateRequest` from body.

### RestRenderSearchTemplateAction
- Routes: `GET/POST /_render/template`, `GET/POST /_render/template/{id}`
- Sets `simulate=true` so no search is executed, only rendering.

### RestMultiSearchTemplateAction
- Routes: `GET/POST /_msearch/template`, `GET/POST /{index}/_msearch/template`
- Parses multi-line format (header line + body line per request).

---

## 12. Test Files

### MustacheScriptEngineTests
**File:** `src/test/java/org/opensearch/script/mustache/MustacheScriptEngineTests.java`

- Tests parameter replacement (`{{boost_val}}`, `{{body_val}}`).
- Tests conditional clauses (`{{#use_it}}...{{/use_it}}`).
- Tests JSON escape encoding (special chars, unicode).
- Demonstrates compile -> newInstance -> execute flow.

### MustacheTests
**File:** `src/test/java/org/opensearch/script/mustache/MustacheTests.java`

- Tests `{{data.0}}` array access, nested array access, map-in-array access.
- Tests `.size` property on arrays and collections.
- Tests `{{#toJson}}` with: primitives, maps, nested maps, arrays, lists, `.` (whole context).
- Tests `{{#join}}` with arrays of various types, embedded arrays, custom delimiters.
- Tests `{{#url}}` URL encoding, combined with join.
- Tests error cases: multiple identifiers inside toJson/join throw errors.

### CustomMustacheFactoryTests
**File:** `src/test/java/org/opensearch/script/mustache/CustomMustacheFactoryTests.java`

- Tests encoder creation for various MIME types.
- Tests JsonEscapeEncoder (default), DefaultEncoder (plain text), UrlEncoder behavior.

---

## 13. com.github.mustachejava Classes Used

| Class | Package | Used In | Purpose |
|-------|---------|---------|---------|
| `Code` | `com.github.mustachejava` | `CustomMustacheFactory` | Interface for compiled template elements; `getCodes()` returns child codes |
| `DefaultMustacheFactory` | `com.github.mustachejava` | `CustomMustacheFactory` | Base factory; its `compile()` method parses template into AST of Code objects |
| `DefaultMustacheVisitor` | `com.github.mustachejava` | `CustomMustacheVisitor` | Base visitor; provides `list` (ArrayList of Code) and `iterable()` callback |
| `Mustache` | `com.github.mustachejava` | `MustacheScriptEngine` | Interface for compiled templates; has `execute(Writer, Object)` and `getCodes()` |
| `MustacheException` | `com.github.mustachejava` | Multiple | Exception type |
| `MustacheVisitor` | `com.github.mustachejava` | `CustomMustacheFactory` | Interface returned by `createMustacheVisitor()` |
| `MustacheFactory` | `com.github.mustachejava` | `MustacheScriptEngine` | Interface for `compile(Reader, String)` |
| `TemplateContext` | `com.github.mustachejava` | `CustomMustacheFactory` | Holds line/column info, start/end chars for tags |
| `DefaultMustache` | `com.github.mustachejava.codes` | `UrlEncoderCode` | Base class for compiled mustache; has `getCodes()`, `run()` |
| `IterableCode` | `com.github.mustachejava.codes` | `CustomCode`, `CustomMustacheVisitor` | Code for section tags (`{{#var}}...{{/var}}`); has `get(scopes)`, `handle()` |
| `WriteCode` | `com.github.mustachejava.codes` | `CustomCode.extractVariableName` | Code for literal text; used to detect plain-text variable names inside sections |
| `ReflectionObjectHandler` | `com.github.mustachejava.reflect` | `CustomReflectionObjectHandler` | Default handler for resolving variables from scope objects |

---

## 14. Compilation Chain Diagram

```
MustachePlugin.getScriptEngine()
  |
  v
MustacheScriptEngine (registered as "mustache" language)
  |
  compile(name, source, context, options)
  |
  v
CustomMustacheFactory(mimeType)
  |-- sets ObjectHandler -> CustomReflectionObjectHandler
  |-- sets encoder based on MIME type
  |-- overrides createMustacheVisitor() -> CustomMustacheVisitor
  |
  factory.compile(reader, "query-template")
  |
  |-- [mustache.java library parses template]
  |-- For each {{variable}} -> creates ValueCode (standard)
  |-- For each {{#section}}...{{/section}} -> calls visitor.iterable()
  |     |
  |     v
  |   CustomMustacheVisitor.iterable(tc, variable, mustache)
  |     |-- if "toJson" -> new ToJsonCode(tc, df, mustache, variable)
  |     |-- if "join" -> new JoinerCode(tc, df, mustache)
  |     |-- if "join delimiter='...'" -> new CustomJoinerCode(tc, df, mustache, variable)
  |     |-- if "url" -> new UrlEncoderCode(tc, df, mustache, variable)
  |     |-- else -> new IterableCode(tc, df, mustache, variable)  [standard]
  |
  v
com.github.mustachejava.Mustache (compiled AST of Code objects)
  |
  wrapped by: TemplateScript.Factory = params -> new MustacheExecutableScript(template, params)
  |
  v
TemplateScript.Factory (returned to caller)
  |
  .newInstance(params) -> MustacheExecutableScript
  |
  .execute() -> template.execute(writer, params) -> rendered String
```

---

## 15. Key Observations for Programmatic Parameter Extraction

### What Exists

1. **`Mustache.getCodes()`** returns the compiled AST as `Code[]`. This is accessible on any compiled `Mustache` object.
2. **`CustomCode.extractVariableName()`** demonstrates walking the AST at compile time -- it calls `mustache.getCodes()` and checks `instanceof WriteCode`.
3. **`Code.identity(Writer)`** writes the identity of a code (its tag representation) to a writer.
4. The compiled `Mustache` object (returned by `factory.compile()`) is of type `DefaultMustache` which has `getCodes()`.

### What Does NOT Exist

1. **No built-in method** to extract all variable names from a compiled template. The `extractVariableName()` only works for the single identifier inside custom functions (toJson/join).
2. **No public API** on `MustacheScriptEngine` or `CustomMustacheFactory` for AST inspection. The compiled `Mustache` is immediately wrapped in a lambda and the reference is lost.
3. **No `ValueCode` import** -- the library does have `com.github.mustachejava.codes.ValueCode` for `{{variable}}` references, but OpenSearch never imports or references it.

### Strategy for Parameter Extraction

To extract parameter names from a Mustache template, you would need to:

1. **Compile the template** using `CustomMustacheFactory.compile(reader, name)` to get a `Mustache` object.
2. **Walk the code tree recursively** using `getCodes()` on each node.
3. **Identify variable references** by checking for code types:
   - `ValueCode` (from `com.github.mustachejava.codes.ValueCode`) -- represents `{{variable}}` references. The variable name can be obtained via `getName()` or `identity()`.
   - `IterableCode` -- represents `{{#section}}...{{/section}}`. The variable is the section name (conditional/loop variable). Children can be walked recursively.
   - `WriteCode` -- literal text, not a variable.
   - Custom codes (`ToJsonCode`, `JoinerCode`, etc.) -- the variable name is extracted at construction time and stored in the superclass `IterableCode`'s name field.

4. The mustache.java `Code` interface provides:
   - `getCodes()` -- returns child codes (for composite codes).
   - `identity(Writer)` -- writes the tag identity (e.g., `{{name}}`).
   - `getName()` -- returns the variable/section name (available on most Code implementations via the `name` field).

### Accessing the Compiled Mustache Object

Currently, `MustacheScriptEngine.compile()` wraps the `Mustache` object in a lambda and returns `TemplateScript.Factory`. The `Mustache` object is only accessible as a private field of `MustacheExecutableScript`. To extract parameters programmatically, you would either:

- **Option A:** Compile the template independently using `new CustomMustacheFactory().compile(new StringReader(source), "name")` and walk the resulting `Mustache` object's code tree. This does NOT require modifying OpenSearch core.
- **Option B:** Modify `MustacheScriptEngine` to expose the compiled `Mustache` object or add a parameter extraction method.

**Option A is the recommended approach** for ml-commons since it requires no changes to OpenSearch core.

---

## 16. Complete File Inventory

### Main Source Files (18 files)

| File | Public/Internal | Purpose |
|------|-----------------|---------|
| `CustomMustacheFactory.java` | `public` (class), inner classes are package-private | Factory, visitor, custom codes, encoders |
| `CustomReflectionObjectHandler.java` | `final class` (package-private) | Array/collection handling for Mustache |
| `MustacheScriptEngine.java` | `public final` | ScriptEngine implementation, compilation |
| `MustachePlugin.java` | `public` | Plugin entry point |
| `SearchTemplateAction.java` | `public` | ActionType singleton for search template |
| `RenderSearchTemplateAction.java` | `public` | ActionType singleton for render |
| `MultiSearchTemplateAction.java` | `public` | ActionType singleton for multi-search template |
| `SearchTemplateRequest.java` | `public` | Request model |
| `SearchTemplateResponse.java` | `public` | Response model |
| `SearchTemplateRequestBuilder.java` | `public` | Builder pattern for SearchTemplateRequest |
| `MultiSearchTemplateRequest.java` | `public` | Multi-request model |
| `MultiSearchTemplateResponse.java` | `public` | Multi-response model |
| `TransportSearchTemplateAction.java` | `public` | Transport action (has public `convert()` method) |
| `TransportRenderSearchTemplateAction.java` | `public` | Thin extension of TransportSearchTemplateAction |
| `TransportMultiSearchTemplateAction.java` | `public` | Multi-search transport action |
| `RestSearchTemplateAction.java` | `public` | REST endpoint |
| `RestMultiSearchTemplateAction.java` | `public` | REST endpoint |
| `RestRenderSearchTemplateAction.java` | `public` | REST endpoint |

### Test Files (8 files)

| File | What it tests |
|------|---------------|
| `MustacheScriptEngineTests.java` | Compile/execute flow, JSON encoding |
| `MustacheTests.java` | All custom functions (toJson, join, url), array access, error cases |
| `CustomMustacheFactoryTests.java` | Encoder selection and behavior per MIME type |
| `SearchTemplateRequestTests.java` | Request serialization |
| `SearchTemplateRequestXContentTests.java` | XContent parsing |
| `SearchTemplateResponseTests.java` | Response serialization |
| `MultiSearchTemplateRequestTests.java` | Multi-request parsing |
| `MultiSearchTemplateResponseTests.java` | Multi-response serialization |

### Server Core Files

| File | Relevance |
|------|-----------|
| `ScriptEngine.java` | Interface that `MustacheScriptEngine` implements |
| `TemplateScript.java` | Abstract class for template execution; defines `Factory` and `CONTEXT` |
| `ScriptService.java` | Orchestrates compilation with caching, type/context validation |
