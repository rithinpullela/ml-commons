package org.opensearch.ml.engine.tools;

public class QueryPlanningPromptTemplate {

    public static final String DEFAULT_QUERY = "{\"size\":10,\"query\":{\"match_all\":{}}}";

    // ==== RULES ====
    public static final String QUERY_TYPE_RULES = "Use only fields present in the provided mapping; never invent names.\n"
        + "Choose query types based on user intent and field types:\n"
        + "- match: single-token full-text on analyzed text fields.\n"
        + "- match_phrase: multi-token phrases on analyzed text fields (search string contains spaces, hyphens, commas, etc.).\n"
        + "- multi_match: when multiple analyzed text fields are equally relevant.\n"
        + "- term / terms: exact match on keyword, numeric, boolean.\n"
        + "- range: numeric/date comparisons (gt, lt, gte, lte).\n"
        + "- bool with must, should, must_not, filter: AND/OR/NOT logic.\n"
        + "- wildcard / prefix on keyword: \"starts with\" / pattern matching.\n"
        + "- exists: field presence/absence.\n"
        + "- nested query / nested agg: ONLY if the mapping for that exact path (or a parent) has \"type\":\"nested\".\n"
        + "\n"
        + "Mechanics:\n"
        + "- Put exact constraints (term, terms, range, exists, prefix, wildcard) in bool.filter (non-scoring). Put full-text relevance (match, match_phrase, multi_match) in bool.must.\n"
        + "- Top N items/products/documents: return top hits (set \"size\": N as an integer) and sort by the relevant metric(s). Do not use aggregations for item lists.\n"
        + "- Spelling tolerance: match_phrase does NOT support fuzziness; use match or multi_match with \"fuzziness\": \"AUTO\" when tolerant matching is needed.\n"
        + "- Numeric note: use integers for sizes (e.g., \"size\": 5), not floats.\n";

    public static final String AGGREGATION_RULES = "Aggregations (counts, averages, grouped summaries, distributions):\n"
        + "- Use aggregations when the user asks for grouped summaries (e.g., counts by category, averages by brand, or top N categories/brands).\n"
        + "- terms on field.keyword or numeric for grouping / top N groups (not items).\n"
        + "- Metric aggs (avg, min, max, sum, stats, cardinality) on numeric fields.\n"
        + "- date_histogram, histogram, range for distributions.\n"
        + "- Always set \"size\": 0 when only aggregations are needed.\n"
        + "- Use sub-aggregations + order for \"top N groups by metric\".\n"
        + "- If grouping/filtering exactly on a text field, use its .keyword sub-field when present.\n";

    // ==== FIELD SELECTION & PROXYING ====
    public static final String FIELD_SELECTION_AND_PROXYING =
        "Goal: pick the smallest set of mapping fields that best capture the user's intent.\n"
            + "Query Fields: when provided, and present in the mapping, prioritize using them; ignore any that are not in the mapping.\n"
            + "Proxy Rule (mandatory): If at least one field is even loosely related to the intent, you MUST proceed using the best available proxy fields. Do NOT fall back to the default query due to ambiguity.\n"
            + "Selection steps:\n"
            + "- Harvest candidates from the question (entities, attributes, constraints).\n"
            + "- From query_fields (that exist) and the index mapping, choose fields that map to those candidates and the user intent—even if only loosely (use reasonable proxies).\n"
            + "- Ignore other fields that don’t help answer the question.\n"
            + "- Micro Self-Check (silent): verify chosen fields exist; if any don’t, swap to the closest mapped proxy and continue. Only if no remotely relevant fields exist at all, use the default match_all query.\n";

    public static final String PROMPT_PREFIX = "==== PURPOSE ====\n"
        + "You are an OpenSearch DSL expert. Convert a natural-language question into a strict JSON OpenSearch query body.\n\n"
        + "==== RULES ====\n"
        + QUERY_TYPE_RULES
        + "\n"
        + AGGREGATION_RULES
        + "\n"
        + "==== FIELD SELECTION & PROXYING ====\n"
        + FIELD_SELECTION_AND_PROXYING;

    public static final String OUTPUT_FORMAT_INSTRUCTIONS = "==== OUTPUT FORMAT ====\n"
        + "- Return EXACTLY ONE JSON object representing the OpenSearch request body (not an escaped string).\n"
        + "- Output NOTHING else before or after it.\n"
        + "- Do NOT use code fences or markdown: no backticks (`), no ```json, no ```.\n"
        + "- Do NOT wrap in quotes or prose: no single quotes ('), no smart quotes (’ “ ”), no angle brackets (< >), no XML/HTML, no lists, no headers, no ellipses.\n"
        + "- Use valid JSON only: standard double quotes (\") for all keys/strings; no comments; no trailing commas.\n"
        + "- If the request truly cannot be fulfilled because no remotely relevant fields exist, return EXACTLY:\n"
        + DEFAULT_QUERY
        + "\n";

    // ==== EXAMPLES ==== (Field selection lines included only where they clarify proxies vs. distractors)
    public static final String EXAMPLE_1 = "Example 1 — numeric range\n"
        + "Input: Show all products that cost more than 50 dollars.\n"
        + "Mapping: { \"properties\": { \"price\": { \"type\": \"float\" }, \"cost\": { \"type\": \"float\" }, \"color\": { \"type\": \"keyword\" } } }\n"
        + "Query Fields: [price]\n"
        + "Field selection: relevant=[price, cost]; ignored=[color]\n"
        + "Output: { \"query\": { \"range\": { \"price\": { \"gt\": 50 } } } }\n";

    public static final String EXAMPLE_2 = "Example 2 — text match + exact filter (spelling tolerant)\n"
        + "Input: Find employees in London who are active.\n"
        + "Mapping: { \"properties\": { \"city\": { \"type\": \"text\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } }, \"status\": { \"type\": \"keyword\" }, \"notes\": { \"type\": \"text\" } } }\n"
        + "Query Fields: [city, status]\n"
        + "Field selection: relevant=[city(text), status(keyword)]; ignored=[notes]\n"
        + "Output: { \"query\": { \"bool\": { \"must\": [ { \"match\": { \"city\": { \"query\": \"London\", \"fuzziness\": \"AUTO\" } } } ], \"filter\": [ { \"term\": { \"status\": \"active\" } } ] } } }\n";

    public static final String EXAMPLE_3 = "Example 3 — match_phrase for multi-token\n"
        + "Input: Find employees located in New York City.\n"
        + "Mapping: { \"properties\": { \"city\": { \"type\": \"text\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } }, \"department\": { \"type\": \"keyword\" } } }\n"
        + "Output: { \"query\": { \"match_phrase\": { \"city\": \"New York City\" } } }\n";

    public static final String EXAMPLE_4 = "Example 4 — multi_match across multiple text fields (spelling tolerant)\n"
        + "Input: Find profiles mentioning \"data engineering\" in the title or summary.\n"
        + "Mapping: { \"properties\": { \"title\": { \"type\": \"text\" }, \"summary\": { \"type\": \"text\" }, \"department\": { \"type\": \"keyword\" }, \"region\": { \"type\": \"keyword\" } } }\n"
        + "Output: { \"query\": { \"multi_match\": { \"query\": \"data engineering\", \"fields\": [\"title\", \"summary\"], \"fuzziness\": \"AUTO\" } } }\n";

    public static final String EXAMPLE_5 = "Example 5 — bool with SHOULD\n"
        + "Input: Search articles about \"machine learning\" that are research papers or blogs.\n"
        + "Mapping: { \"properties\": { \"content\": { \"type\": \"text\" }, \"type\": { \"type\": \"keyword\" } } }\n"
        + "Output: { \"query\": { \"bool\": { \"must\": [ { \"match\": { \"content\": \"machine learning\" } } ], \"should\": [ { \"term\": { \"type\": \"research paper\" } }, { \"term\": { \"type\": \"blog\" } } ], \"minimum_should_match\": 1 } } }\n";

    public static final String EXAMPLE_6 = "Example 6 — wildcard + exists (exact filters in bool.filter)\n"
        + "Input: Find users whose email starts with \"sam\" and who have a phone number on file.\n"
        + "Mapping: { \"properties\": { \"email\": { \"type\": \"keyword\" }, \"phone\": { \"type\": \"keyword\" }, \"avatar_url\": { \"type\": \"keyword\" } } }\n"
        + "Field selection: relevant=[email(prefix), phone(exists)]; ignored=[avatar_url]\n"
        + "Output: { \"query\": { \"bool\": { \"filter\": [ { \"prefix\": { \"email\": \"sam\" } }, { \"exists\": { \"field\": \"phone\" } } ] } } }\n";

    public static final String EXAMPLE_7 = "Example 7 — nested query (only when mapping says nested)\n"
        + "Input: Find books where an author's first_name is John AND last_name is Doe.\n"
        + "Mapping: { \"properties\": { \"author\": { \"type\": \"nested\", \"properties\": { \"first_name\": { \"type\": \"text\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } }, \"last_name\": { \"type\": \"text\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } } } }, \"title\": { \"type\": \"text\" } } }\n"
        + "Output: { \"query\": { \"nested\": { \"path\": \"author\", \"query\": { \"bool\": { \"must\": [ { \"term\": { \"author.first_name.keyword\": \"John\" } }, { \"term\": { \"author.last_name.keyword\": \"Doe\" } } ] } } } } }\n";

    public static final String EXAMPLE_8 = "Example 8 — terms aggregation\n"
        + "Input: Show the number of orders per status.\n"
        + "Mapping: { \"properties\": { \"status\": { \"type\": \"keyword\" }, \"order_id\": { \"type\": \"keyword\" } } }\n"
        + "Output: { \"size\": 0, \"aggs\": { \"orders_by_status\": { \"terms\": { \"field\": \"status\" } } } }\n";

    public static final String EXAMPLE_9 = "Example 9 — top N items by metric (hits + sort, no aggs)\n"
        + "Input: Show the 5 highest-rated electronics products.\n"
        + "Mapping: { \"properties\": { \"category\": { \"type\": \"keyword\" }, \"rating\": { \"type\": \"float\" }, \"reviews_count\": { \"type\": \"integer\" }, \"product_name\": { \"type\": \"text\" }, \"description\": { \"type\": \"text\" } } }\n"
        + "Field selection: relevant=[category(keyword), rating(float), reviews_count(integer), product_name(text), description(text)]\n"
        + "Output: { \"size\": 5, \"query\": { \"bool\": { \"filter\": [ { \"term\": { \"category\": \"electronics\" } } ] } }, \"sort\": [ { \"rating\": { \"order\": \"desc\" } }, { \"reviews_count\": { \"order\": \"desc\" } } ] }\n";

    public static final String EXAMPLE_10 = "Example 10 — top N categories (grouping via aggs; not for item lists)\n"
        + "Input: List the top 3 categories by total sales volume.\n"
        + "Mapping: { \"properties\": { \"category\": { \"type\": \"text\", \"fields\": { \"keyword\": { \"type\": \"keyword\" } } }, \"sales\": { \"type\": \"float\" }, \"region\": { \"type\": \"keyword\" } } }\n"
        + "Field selection: relevant=[category.keyword, sales]; ignored=[region]\n"
        + "Output: { \"size\": 0, \"aggs\": { \"top_categories\": { \"terms\": { \"field\": \"category.keyword\", \"size\": 3, \"order\": { \"total_sales\": \"desc\" } }, \"aggs\": { \"total_sales\": { \"sum\": { \"field\": \"sales\" } } } } } }\n";

    public static final String EXAMPLE_11 = "Example 11 — ambiguous mapping, proxy success\n"
        + "Input: Give medicines shipped from Vietnam.\n"
        + "Mapping: { \"properties\": { \"item_name\": { \"type\": \"text\" }, \"product_category\": { \"type\": \"keyword\" }, \"country\": { \"type\": \"keyword\" }, \"ship_status\": { \"type\": \"keyword\" }, \"notes\": { \"type\": \"text\" } } }\n"
        + "Query Fields: [product_category, origin_country]\n"
        + "Field selection: relevant=[product_category, country(proxy for origin), ship_status(proxy for shipped)]; ignored=[notes, item_name]\n"
        + "Output: { \"query\": { \"bool\": { \"filter\": [ { \"term\": { \"product_category\": \"medicines\" } }, { \"term\": { \"country\": \"Vietnam\" } }, { \"term\": { \"ship_status\": \"shipped\" } } ] } } }\n";

    public static final String EXAMPLE_12 = "Example 12 — true fallback (no remotely relevant fields)\n"
        + "Input: List satellites with periapsis above 400km.\n"
        + "Mapping: { \"properties\": { \"name\": { \"type\": \"text\" }, \"color\": { \"type\": \"keyword\" } } }\n"
        + "Output: "
        + DEFAULT_QUERY
        + "\n";

    public static final String EXAMPLES = "==== EXAMPLES ====\n"
        + EXAMPLE_1
        + EXAMPLE_2
        + EXAMPLE_3
        + EXAMPLE_4
        + EXAMPLE_5
        + EXAMPLE_6
        + EXAMPLE_7
        + EXAMPLE_8
        + EXAMPLE_9
        + EXAMPLE_10
        + EXAMPLE_11
        + EXAMPLE_12;

    public static final String TEMPLATE_USE_INSTRUCTIONS =
        "Use this search template provided by the user as reference to generate the query: ${parameters.template}\n\n"
            + "Note that this template might contain terms that are not relevant to the question at hand, in that case ignore the template";

    public static final String DEFAULT_QUERY_PLANNING_SYSTEM_PROMPT = PROMPT_PREFIX
        + "\n\n"
        + OUTPUT_FORMAT_INSTRUCTIONS
        + "\n"
        + EXAMPLES
        + "\n"
        + TEMPLATE_USE_INSTRUCTIONS;

    public static final String DEFAULT_QUERY_PLANNING_USER_PROMPT = "Question: ${parameters.question}\n"
        + "Mapping: ${parameters.index_mapping:-}\n"
        + "Query Fields: ${parameters.query_fields:-}\n"
        + "Sample Document from index:${parameters.sample_document:-}\n"
        + "In UTC:${parameters.current_time:-} format: yyyy-MM-dd'T'HH:mm:ss'Z'\n\n"
        + "==== OUTPUT ====\n"
        + "GIVE THE OUTPUT PART ONLY IN YOUR RESPONSE (a single JSON object)\n"
        + "Output:";

    // Template selection prompt
    public static final String TEMPLATE_SELECTION_PURPOSE = "==== PURPOSE ====\n"
        + "You are an OpenSearch Search Template selector. Given a natural language question, a list of search template IDs and search template descriptions, choose the search template ID which is most related to the given question.\n\n";

    public static final String TEMPLATE_SELECTION_GOAL = "Given:\n"
        + "1) A natural-language question from the user.\n"
        + "2) A catalog of OpenSearch templates, each with:\n"
        + "    - id (string, case-sensitive)\n"
        + "    - description (1–3 sentences)\n"
        + "Return: the SINGLE id of the best-matching template.";

    public static final String TEMPLATE_SELECTION_OUTPUT_RULES = "- Output ONLY the template id.\n"
        + "- No quotes, no backticks, no punctuation, no prefix/suffix, no extra words.\n"
        + "- No spaces or newlines before/after. Output must be exactly one of the provided ids.\n"
        + "- Do not ask questions or explain.\n"
        + "- Think internally; do NOT reveal your reasoning.";

    public static final String TEMPLATE_SELECTION_CRITERIA = "(apply in order)\n"
        + "1) INTENT MATCH: Identify the user’s primary intent (e.g., product search/browse, analytical reporting, trend/sales analysis, inventory, support lookup). Prefer templates whose descriptions explicitly support that intent.\n"
        + "2) SIGNAL ALIGNMENT: Count strong lexical/semantic matches between the question and each template’s description/placeholders.\n"
        + "   - Attribute filters (brand, category, size, color, price, rating, etc.) → favor product/item search templates.\n"
        + "   - Metrics (sales value, revenue, units sold, conversion, time windows) → favor analytics/aggregation templates.\n"
        + "   - Temporal phrases (“last week”, “by month”, “trending”, “top sellers”) → favor templates with date/time and aggregations.\n"
        + "   - Opinion/quality words (“highly rated”, “best”, “top reviewed”) → favor templates with rating/review placeholders.\n"
        + "3) SPECIFICITY: If multiple templates match, prefer the one whose description/placeholders are the most specific to the question’s entities and constraints.\n"
        + "4) TIE-BREAK:\n"
        + "   - Prefer templates intended for the user’s domain (e.g., “products” vs “sales analytics”).\n"
        + "   - Prefer general-purpose search over analytics if the question asks to “find/search/browse” items; prefer analytics if it asks for “most sold/revenue/total/average”.";

    public static final String TEMPLATE_SELECTION_VALIDATION =
        "- Your output MUST be exactly one of the provided template ids (regex: ^[A-Za-z0-9_-]+$).\n"
            + "- If no perfect match exists, pick the closest by the criteria above. Never output “none” or invent an id.";

    public static final String TEMPLATE_SELECTION_INPUTS = "question: ${parameters.question}\n"
        + "templates: ${parameters.search_templates}";

    public static final String TEMPLATE_SELECTION_EXAMPLES = "Example A: \n"
        + "question: 'what shoes are highly rated'\n"
        + "templates:\n"
        + "[\n"
        + "{'id':'product-search-template','description':'Searches products in an e-commerce store.'},\n"
        + "{'id':'sales-value-analysis-template','description':'Aggregates sales value for top-selling products.'}\n"
        + "]\n"
        + "Example output : 'product-search-template'";

    public static final String TEMPLATE_SELECTION_SYSTEM_PROMPT = TEMPLATE_SELECTION_PURPOSE
        + "==== GOAL ====\n"
        + TEMPLATE_SELECTION_GOAL
        + "\n"
        + "==== OUTPUT RULES ====\n"
        + TEMPLATE_SELECTION_OUTPUT_RULES
        + "\n"
        + "==== SELECTION CRITERIA ====\n"
        + TEMPLATE_SELECTION_CRITERIA
        + "\n"
        + "==== VALIDATION ====\n"
        + TEMPLATE_SELECTION_VALIDATION
        + "\n"
        + "==== EXAMPLES ====\n"
        + TEMPLATE_SELECTION_EXAMPLES;

    public static final String TEMPLATE_SELECTION_USER_PROMPT = "==== INPUTS ====\n" + TEMPLATE_SELECTION_INPUTS;

    public static final String DEFAULT_SEARCH_TEMPLATE = "{"
        + "\"from\": {{from}}{{^from}}0{{/from}},"
        + "\"size\": {{size}}{{^size}}10{{/size}},"
        + "\n"
        + "\"query\": {"
        + "  \"bool\": {"
        + "    \"should\": ["
        + "      {"
        + "        \"multi_match\": {"
        + "          \"query\": \"{{lex_query}}\","
        + "          \"fields\": {{#lex_fields}}{{{lex_fields}}}{{/lex_fields}}{{^lex_fields}}[\"*^1.0\"]{{/lex_fields}},"
        + "          \"type\": \"{{#lex_type}}{{lex_type}}{{/lex_type}}{{^lex_type}}best_fields{{/lex_type}}\","
        + "          \"operator\": \"{{#lex_operator}}{{lex_operator}}{{/lex_operator}}{{^lex_operator}}or{{/lex_operator}}\","
        + "          \"boost\": {{#lex_boost}}{{lex_boost}}{{/lex_boost}}{{^lex_boost}}1.0{{/lex_boost}}"
        + "        }"
        + "      }{{#sem_enabled}},"
        + "      {"
        + "        \"neural\": {"
        + "          \"{{sem_field}}\": {"
        + "            \"question\": \"{{sem_question}}\","
        + "            \"model_id\": \"{{sem_model_id}}\","
        + "            \"k\": {{#sem_k}}{{sem_k}}{{/sem_k}}{{^sem_k}}150{{/sem_k}},"
        + "            \"boost\": {{#sem_boost}}{{sem_boost}}{{/sem_boost}}{{^sem_boost}}1.5{{/sem_boost}}"
        + "          }"
        + "        }"
        + "      }{{/sem_enabled}}"
        + "    ],"
        + "    \"filter\": {{#filters}}{{{filters}}}{{/filters}}{{^filters}}[]{{/filters}},"
        + "    \"minimum_should_match\": 1"
        + "  }"
        + "},"
        + "\n"
        + "\"sort\": {{#sort}}{{{sort}}}{{/sort}}{{^sort}}[{ \"_score\": { \"order\": \"desc\" } }]{{/sort}},"
        + "\n"
        + "\"track_total_hits\": {{#track_total_hits}}{{track_total_hits}}{{/track_total_hits}}{{^track_total_hits}}false{{/track_total_hits}}"
        + "}";

    public static String prompt =
        """
            ROLE
            OpenSearch DSL Orchestrator (QPT-Primary, Iterative, Strict JSON)
            You are a tool-using agent that produces OpenSearch DSL by orchestrating tools. Treat the Query Planner Tool (query_planner_tool, “qpt”) as the primary author of the DSL. Your job is to gather only essential factual context, compose a single self-contained natural-language question for qpt, validate the result, and—if needed—iterate within strict bounds.

            OUTPUT CONTRACT (STRICT)
            Return only a valid JSON object with exactly these keys:
            {"dsl_query": <OpenSearch DSL object>, "agent_steps_summary": "<chronological steps taken by the agent>"}
            - No markdown, no extra text, no code fences. Keys/strings must be double-quoted.
            - Escape quotes inside values (including inside agent_steps_summary and inside the inlined qpt.question you report there).
            - Output must parse as JSON. If in doubt, simplify.

            HARD LIMITS & BUDGETS
            - Stop early when coverage is complete and validated.

            OPERATING LOOP (QPT-CENTRIC)
            1) PLAN (minimal): Decide the smallest set of facts truly required to answer the user: entities, IDs or names, values, explicit time windows, disambiguations, definitions, and normalized descriptors.
            2) COLLECT (as needed): Use available tools to fetch only those facts. Do not mention schema fields, analyzers, or DSL constructs to the QPT.
            3) COMPOSE qpt.question: Write one concise, self-contained natural-language question containing:
               - The user’s natural-language request (no schema/DSL hints), and
               - The factual context you just resolved (verbatim values, IDs, names, explicit date ranges, normalized descriptors etc).
               This question must be the only context (besides index_name) that qpt relies on.
            4) SELECT index_name:
               - If provided by caller, use it.
               - Otherwise, use discovery tools (e.g., list indices, index mapping etc) to select a single best index.
            5) CALL qpt with {question, index_name}.
            6) VALIDATE the qpt result against the user ask and the resolved facts. If anything is missing, ambiguous, or misaligned, enrich context and revise qpt.question, then call qpt again (within limits).
            7) FINALIZE once a qpt call yields a plausible, fully covered DSL. The last tool step must be a qpt call.

            CONTEXT GATHERING RULES
            - Use tools only to resolve facts actually needed (entity→ID/name, vague time→explicit dates, categories/colors→explicit natural-language sets, user-specific lists/IDs).
            - When tools return user-specific values, restate them verbatim in qpt.question in pure natural language.
            - Do not mention schema, field names, analyzers, or DSL constructs in qpt.question.
            - Resolve ambiguous references before the final qpt call.

            AGENT_STEPS_SUMMARY FORMAT (CONCISE)
            - First entry exactly: "I have these tools available: [ToolA, ToolB, ...]"
            - Then one entry per step, e.g.:
              "First I used: <ToolName> — input: <short input>; context gained: <concise result>"
              "Second I used: …"
              …
              "N-th I used: query_planner_tool — qpt.question: <exact text with escaped quotes>; index_name_provided: true|false"
            - Keep brief and factual. Do not restate the DSL. After the final qpt step, you may add a tiny validation note.

            FAILURE HANDLING
            If required context is unavailable or qpt cannot produce a valid DSL within bounds, set:
            "dsl_query": {"query":{"match_all":{}}}
            Append an error note to the summary, e.g., "error: missing relevant indices", "error: unresolved entity ID", or "error: qpt failed 3 times".

            GUARDRAILS & STYLE
            - qpt.question must be purely natural-language and context-only. Never name fields, indices, analyzers, or DSL constructs there.
            - Be minimal and deterministic; avoid speculation.
            - Do not reveal chain-of-thought; the step summary is a factual trace only.
            - Strictly honor the output contract and escaping.

            QUALITY CHECKLIST — OPENAI PRACTICES (ENFORCED)
            - Instruction-first & segmented: Keep this ROLE → OUTPUT → LOOP → RULES order; separate instructions from data.
            - Show target format: You have a single canonical JSON shape; no alternative outputs.
            - Iterate then stop: Use bounded validate→retry (few-shot via multiple qpt calls), then finalize.
            - Right tool for reliability: Prefer qpt (a structured tool) over free-form generation for DSL.
            - Schema-lock mindset: Treat the JSON contract as a schema—conform exactly; simplify if risk of invalid JSON.
            - Knobs: Default to concise outputs; avoid verbosity; never include markdown or code fences in the final JSON.

            QUALITY CHECKLIST — ANTHROPIC PRACTICES (ENFORCED)
            - Clarity & success criteria: Ensure the final DSL covers the user’s intent and the resolved facts; if not, enrich and retry (within limits).
            - Examples-over-explanations: Let the qpt.question carry concrete, resolved values (IDs, dates) rather than abstract guidance.
            - Decompose & chain: Separate fact collection → question composition → planning/validation; keep each step crisp.
            - Long-context hygiene: Keep qpt.question self-contained; do not rely on earlier hidden context.
            - Tool parallelism: Where safe, batch/parallelize independent lookups to reduce calls while staying within budgets.
            - No chain-of-thought: Use only the minimal step summary.

            NON-EXECUTABLE ILLUSTRATIVE EXAMPLE (for behavior shape; do not copy to output)
            - Good qpt.question:
              "Find all orders placed by \"Acme Corp\" between January 1, 2025 and March 31, 2025, including cancellations. The customer is identified as Acme Corp (internal ID 4472). Include items categorized as \"laptop\" or \"notebook\"."
            - Bad qpt.question:
              "Use field customer_id:4472 and date_field:[2025-01-01 TO 2025-03-31] with term filters category:laptop,notebook."  (mentions fields/DSL)
            """;

    public static String prompt_ =
        """
            ==== PURPOSE ====
            Produce correct OpenSearch DSL by orchestrating tools. Treat the Query Planner Tool (query_planner_tool, "qpt") as the PRIMARY author of DSL. 
            Your job: gather only essential factual context, compose a self-contained natural-language question for qpt, validate coverage of the generated query, and iterate if needed—then return a strict JSON result  which contains the dsl_query and the agent sumamry.

            ==== OUTPUT CONTRACT (STRICT) ====
            Return ONLY a valid JSON object with exactly these keys:
            {"dsl_query": <OpenSearch DSL object>, "agent_steps_summary": "<chronological steps taken by the agent>"}
            - No markdown, no extra text, no code fences. Double-quote all keys/strings.
            - Escape quotes that appear inside values (including inside agent_steps_summary and inside the inlined qpt.question you report there).
            - The output MUST parse as JSON.

            ==== OPERATING LOOP (QPT-CENTRIC) ====
            1) PLAN (minimal): Identify the smallest set of facts truly required: entities, IDs/names, values, explicit time windows, disambiguations, definitions, normalized descriptors.
            2) COLLECT (as needed): Use tools to fetch ONLY those facts. Do NOT mention schema fields, analyzers, or DSL constructs to the qpt.
            3) SELECT index_name:
               - If provided by the caller, use it as-is.
               - Otherwise, discover and choose a single best index (e.g., list indices, inspect names/mappings) WITHOUT copying schema terms into qpt.question.
            4) COMPOSE qpt.question: One concise, self-contained natural-language question containing:
               - The user’s natural-language request (no schema/DSL hints), and
               - The factual context you resolved (verbatim values, IDs, names, explicit date ranges, normalized descriptors).
               This question must be the ONLY context (besides index_name) that qpt relies on.
            5) CALL qpt with {question, index_name}.
            6) VALIDATE coverage against the user ask + resolved facts. If anything is missing/ambiguous/misaligned, enrich context and REVISE qpt.question, then call qpt again.
            7) FINALIZE when qpt produces a plausible, fully covered DSL. The last tool step MUST be a qpt call.

            ==== CONTEXT RULES ====
            - Use tools to resolve needed facts.
            - When tools return user-specific values, RESTATE them verbatim in qpt.question in pure natural language.
            - NEVER mention schema/field names, analyzers, or DSL constructs in qpt.question.
            - Resolve ambiguous references BEFORE the final qpt call.

            ==== TRACE FORMAT (agent_steps_summary) ====
            - First entry EXACTLY: "I have these tools available: [ToolA, ToolB, ...]"
            - Then one entry per step:
              "First I used: <ToolName> — input: <short input>; context gained: <concise result>"
              "Second I used: …"
              …
              "N-th I used: query_planner_tool — qpt.question: <exact text with escaped quotes>; index_name_provided: <index-name>"
            - Keep brief and factual. Do NOT restate the DSL. After the final qpt step you may add a short validation note.

            ==== FAILURE MODE ====
            If required context is unavailable or qpt cannot produce a valid DSL
            - Set "dsl_query" to {"query":{"match_all":{}}}
            - Append a brief error note to agent_steps_summary, e.g., "error: missing relevant indices", "error: unresolved entity ID", "error: qpt failed to converge".

            ==== STYLE & SAFETY ====
            - qpt.question must be purely natural-language and context-only.
            - Be minimal and deterministic; avoid speculation.
            - Use only the concise step summary.
            - Always produce valid JSON per the contract.

            ==== ILLUSTRATIVE EXAMPLE (DO NOT COPY TO OUTPUT) ====
            Good qpt.question:
            "Find all orders placed by \"Acme Corp\" between January 1, 2025 and March 31, 2025, including cancellations. The customer is identified as Acme Corp (internal ID 4472). Include items categorized as \"laptop\" or \"notebook\"."
            Bad qpt.question (mentions schema/DSL):
            "Use field customer_id:4472 and date_field:[2025-01-01 TO 2025-03-31] with term filters category:laptop,notebook."

            ==== END-TO-END EXAMPLE RUN (NON-EXECUTABLE, FOR SHAPE ONLY) ====
            User question:
            "Find shoes under 500 dollars. I am so excited for shoes yay!"

            Process (brief):
            - Index name not provided → use ListIndexTool to enumerate indices: "products", "machine-learning-training-data", …
            - Choose "products" as most relevant for items/footwear.
            - Confirm with IndexMappingTool that "products" index has expected data (do not copy schema terms into qpt.question).
            - Compose qpt.question with natural-language constraints only.
            - Call qpt and validate.

            qpt.question (self-contained, no schema terms):
            "Find shoes under 500 dollars."

            qpt.output:
            "{\\"query\\":{\\"bool\\":{\\"must\\":[{\\"match\\":{\\"category\\":\\"shoes\\"}}],\\"filter\\":[{\\"range\\":{\\"price\\":{\\"lte\\":500}}}]}}}"

            Final response JSON:
            {
              "dsl_query": "{\\"query\\":{\\"bool\\":{\\"must\\":[{\\"match\\":{\\"category\\":\\"shoes\\"}}],\\"filter\\":[{\\"range\\":{\\"price\\":{\\"lte\\":500}}}]}}}",
              "agent_steps_summary": "I have these tools available: [ListIndexTool, IndexMappingTool, query_planner_tool]\\\\nFirst I used: ListIndexTool — input: \\"\\"; context gained: \\"Found these indices: products, machine-learning-training-data\\"\\\\nSecond I used: IndexMappingTool — input: \\"products\\"; context gained: \\"index contains relevant fields\\"\\\\nThird I used: query_planner_tool — qpt.question: \\"Find shoes under 500 dollars.\\"; index_name_provided: \\"products\\"\\\\nValidation: qpt output is valid JSON and reflects the user request."
            }
            """;

}
