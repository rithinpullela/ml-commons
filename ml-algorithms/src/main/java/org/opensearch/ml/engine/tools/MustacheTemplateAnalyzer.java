/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.tools;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.mustachejava.Code;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.codes.DefaultCode;
import com.github.mustachejava.codes.IterableCode;
import com.github.mustachejava.codes.NotIterableCode;
import com.github.mustachejava.codes.ValueCode;
import com.github.mustachejava.codes.WriteCode;

import lombok.extern.log4j.Log4j2;

/**
 * Extracts parameter definitions from Mustache search templates using AST walking.
 * <p>
 * Compiles the template with mustache.java ({@code DefaultMustacheFactory}), then
 * recursively walks the {@link Code} tree to discover variables, section controllers,
 * and OpenSearch helper functions (toJson, join, url).
 * <p>
 * For each discovered parameter, determines:
 * <ul>
 *   <li><b>name</b> — from {@link ValueCode}, {@link IterableCode}, or helper content</li>
 *   <li><b>required/optional</b> — from section nesting and inverted-section defaults</li>
 *   <li><b>type</b> — heuristic from DSL context (quoted = string, unquoted = integer, helper = array)</li>
 *   <li><b>description</b> — generated from the surrounding DSL field name and query clause</li>
 * </ul>
 */
@Log4j2
public class MustacheTemplateAnalyzer {

    private static final Set<String> HELPER_FUNCTIONS = Set.of("tojson", "join", "url");
    private static final Pattern JOIN_DELIMITER_PATTERN = Pattern.compile("(?i)^join\\s+delimiter='.*'$");
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"?\\s*$");
    private static final Pattern QUERY_TYPE_PATTERN = Pattern
        .compile("\"(match|match_phrase|term|terms|range|wildcard|prefix|fuzzy|bool|filter|must|should|must_not)\"");

    /**
     * Analyzes a Mustache template source and returns parameter definitions.
     *
     * @param templateSource the raw Mustache template string
     * @return a map of parameter name to its definition (type, description, required)
     * @throws IllegalArgumentException if the template cannot be compiled
     */
    public static Map<String, Map<String, Object>> analyze(String templateSource) {
        if (templateSource == null || templateSource.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        MustacheFactory factory = new DefaultMustacheFactory();
        Mustache compiled;
        try {
            compiled = factory.compile(new StringReader(templateSource), "template");
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse Mustache template: " + e.getMessage(), e);
        }

        Map<String, ParamInfo> params = new LinkedHashMap<>();
        Map<String, String> invertedSectionDefaults = new LinkedHashMap<>();

        collectInvertedSections(compiled.getCodes(), invertedSectionDefaults);
        walkCodes(compiled.getCodes(), params, 0, null, null);

        for (Map.Entry<String, String> entry : invertedSectionDefaults.entrySet()) {
            ParamInfo info = params.get(entry.getKey());
            if (info != null) {
                info.hasInvertedDefault = true;
                info.defaultValue = entry.getValue();
            }
        }

        return buildResult(params);
    }

    /**
     * Recursively walks the Code tree to extract parameter information.
     */
    private static void walkCodes(Code[] codes, Map<String, ParamInfo> params, int depth, String parentSection, String lastWriteText) {
        if (codes == null) {
            return;
        }

        String precedingText = lastWriteText;

        for (Code code : codes) {
            if (code instanceof WriteCode) {
                precedingText = getAppendedText(code);
                continue;
            }

            String name = getCodeName(code);

            if (code instanceof ValueCode) {
                handleValueCode(name, params, depth, parentSection, precedingText);
            } else if (code instanceof IterableCode) {
                handleIterableCode(code, name, params, depth, parentSection, precedingText);
            } else if (code instanceof NotIterableCode) {
                walkCodes(code.getCodes(), params, depth, parentSection, null);
            } else {
                walkCodes(code.getCodes(), params, depth, parentSection, precedingText);
            }

            // The appended text of this code is the preceding text for the next code
            precedingText = getAppendedText(code);
        }
    }

    private static void handleValueCode(String name, Map<String, ParamInfo> params, int depth, String parentSection, String precedingText) {
        if (".".equals(name)) {
            return;
        }

        String rootName = name.contains(".") ? name.split("\\.")[0] : name;
        ParamInfo info = params.computeIfAbsent(rootName, ParamInfo::new);
        info.isSectionControllerOnly = false;
        info.minScopeDepth = Math.min(info.minScopeDepth, depth);

        if (depth == 0) {
            info.appearsAtRootScope = true;
        }
        if (parentSection != null && parentSection.equals(rootName)) {
            info.appearsInsideOwnSection = true;
        }
        if (precedingText != null && info.precedingText == null) {
            info.precedingText = precedingText;
        }
    }

    private static void handleIterableCode(
        Code code,
        String name,
        Map<String, ParamInfo> params,
        int depth,
        String parentSection,
        String precedingText
    ) {
        if (isHelperFunction(name)) {
            handleHelperFunction(code, name, params, depth, parentSection);
            return;
        }

        // Regular section — the section name is itself a parameter
        ParamInfo info = params.computeIfAbsent(name, ParamInfo::new);
        info.minScopeDepth = Math.min(info.minScopeDepth, depth);
        if (depth == 0) {
            info.appearsAtRootScope = true;
        }

        // Pass preceding text into section so inner variables inherit the DSL context
        walkCodes(code.getCodes(), params, depth + 1, name, precedingText);
    }

    private static void handleHelperFunction(Code code, String name, Map<String, ParamInfo> params, int depth, String parentSection) {
        String lowerName = name.toLowerCase(Locale.ROOT);

        if ("url".equals(lowerName)) {
            // url is transparent — recurse into children at same scope
            walkCodes(code.getCodes(), params, depth, parentSection, null);
            return;
        }

        // toJson or join — extract variable name from inner content
        String varName = extractHelperVariable(code);
        if (varName != null) {
            String rootName = varName.contains(".") ? varName.split("\\.")[0] : varName;
            ParamInfo info = params.computeIfAbsent(rootName, ParamInfo::new);
            info.isSectionControllerOnly = false;
            info.isArrayType = true;
            info.minScopeDepth = Math.min(info.minScopeDepth, depth);
            if (depth == 0) {
                info.appearsAtRootScope = true;
            }
            if (parentSection != null && parentSection.equals(rootName)) {
                info.appearsInsideOwnSection = true;
            }
        }
    }

    /**
     * Extracts the variable name from inside a toJson/join helper section.
     * The variable is either plain text ({@code WriteCode}) or a tag ({@code ValueCode}).
     */
    private static String extractHelperVariable(Code code) {
        Code[] inner = code.getCodes();
        if (inner == null || inner.length == 0) {
            return null;
        }

        for (Code child : inner) {
            if (child instanceof WriteCode) {
                String text = getAppendedText(child);
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            } else if (child instanceof ValueCode) {
                return getCodeName(child);
            }
        }
        return null;
    }

    private static void collectInvertedSections(Code[] codes, Map<String, String> defaults) {
        if (codes == null) {
            return;
        }
        for (Code code : codes) {
            if (code instanceof NotIterableCode) {
                String name = getCodeName(code);
                if (name != null) {
                    String defaultValue = extractDefaultValue(code);
                    defaults.put(name, defaultValue);
                }
            }
            collectInvertedSections(code.getCodes(), defaults);
        }
    }

    private static String extractDefaultValue(Code code) {
        Code[] inner = code.getCodes();
        if (inner == null || inner.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Code child : inner) {
            if (child instanceof WriteCode) {
                String text = getAppendedText(child);
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean isHelperFunction(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return HELPER_FUNCTIONS.contains(lower) || JOIN_DELIMITER_PATTERN.matcher(name).matches();
    }

    private static String getCodeName(Code code) {
        if (code instanceof DefaultCode) {
            return ((DefaultCode) code).getName();
        }
        return null;
    }

    /**
     * Extracts the 'appended' text from a Code node. In mustache.java, each code
     * node stores the literal text that follows it (up to the next tag) in a field
     * called 'appended' on DefaultCode.
     */
    private static String getAppendedText(Code code) {
        if (code instanceof DefaultCode) {
            try {
                Field appendedField = DefaultCode.class.getDeclaredField("appended");
                appendedField.setAccessible(true);
                return (String) appendedField.get(code);
            } catch (Exception e) {
                log.debug("Failed to extract appended text from code", e);
            }
        }
        return null;
    }

    // ----- Result building -----

    private static Map<String, Map<String, Object>> buildResult(Map<String, ParamInfo> params) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        for (ParamInfo info : params.values()) {
            Map<String, Object> paramDef = new LinkedHashMap<>();
            paramDef.put("type", inferType(info));
            paramDef.put("description", generateDescription(info));
            paramDef.put("required", isRequired(info));
            if (info.defaultValue != null) {
                paramDef.put("default", info.defaultValue);
            }
            result.put(info.name, paramDef);
        }

        return result;
    }

    private static boolean isRequired(ParamInfo info) {
        if (info.hasInvertedDefault) {
            return false;
        }
        if (info.isSectionControllerOnly) {
            return false;
        }
        // Self-guarding: {{#x}}...{{x}}...{{/x}} — the section disappears when x is absent
        if (info.appearsInsideOwnSection) {
            return false;
        }
        return info.appearsAtRootScope;
    }

    private static String inferType(ParamInfo info) {
        if (info.isArrayType) {
            return "array";
        }
        if (info.isSectionControllerOnly) {
            return "boolean";
        }
        if (info.precedingText != null) {
            String text = info.precedingText.trim();
            // Variable appears inside quotes → string; unquoted → number
            if (text.endsWith("\"")) {
                return "string";
            }
            if (text.endsWith(":") || text.endsWith(",")) {
                return "number";
            }
        }
        return "string";
    }

    private static String generateDescription(ParamInfo info) {
        if (info.isArrayType) {
            return "Value for '" + info.name + "' (array)";
        }
        if (info.isSectionControllerOnly) {
            return "Flag to enable/disable the '" + info.name + "' clause";
        }
        if (info.precedingText != null) {
            String fieldName = extractFieldName(info.precedingText);
            String queryType = extractQueryType(info.precedingText);
            if (fieldName != null && queryType != null) {
                return "Value for the '" + fieldName + "' field (" + queryType + ")";
            } else if (fieldName != null) {
                return "Value for the '" + fieldName + "' field";
            }
        }
        return "Value for '" + info.name + "'";
    }

    private static String extractFieldName(String text) {
        Matcher m = FIELD_NAME_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String extractQueryType(String text) {
        Matcher m = QUERY_TYPE_PATTERN.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    // ----- Internal data class -----

    static class ParamInfo {
        final String name;
        boolean appearsAtRootScope = false;
        boolean appearsInsideOwnSection = false;
        boolean hasInvertedDefault = false;
        boolean isArrayType = false;
        boolean isSectionControllerOnly = true;
        int minScopeDepth = Integer.MAX_VALUE;
        String precedingText = null;
        String defaultValue = null;

        ParamInfo(String name) {
            this.name = name;
        }
    }
}
