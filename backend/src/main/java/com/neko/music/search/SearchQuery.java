/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：保持表达式逐字符等价。
 */

package com.neko.music.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FTS5 {@code MATCH} 表达式编译（纯函数，无 IO，可单测）。
 *
 * <p>所有词元都必须经 {@link #quote(String)} 转义（内部 {@code "} 翻倍），
 * 多列过滤必须写成 {@code {col_a col_b} : expr}。
 */
final class SearchQuery {

    private SearchQuery() {
    }

    static final String METADATA_COLUMNS =
            "title_tok artist_tok album_tok filename_tok pinyin_meta_tok initials_meta_tok reading_meta_tok";
    static final String PINYIN_COLUMNS = "pinyin_meta_tok pinyin_lyrics_tok";
    static final String INITIALS_COLUMNS = "initials_meta_tok initials_lyrics_tok";
    static final String READING_COLUMNS = "reading_meta_tok lyrics_rom_tok";

    /** 用户可见文本列：歌名 / 歌手 / 专辑 / 文件名，不含派生转写列。 */
    static final String VISIBLE_TEXT_COLUMNS = "title_tok artist_tok album_tok filename_tok";

    /** 拉丁单字查询允许的列：可见文本 + 首字母（真实的首字母检索），不含拼音与歌词。 */
    static final String SINGLE_CHAR_LATIN_COLUMNS =
            "title_tok artist_tok album_tok filename_tok initials_meta_tok";

    static final int DEFAULT_MAX_FUZZY_VARIANTS = 500;
    static final int DEFAULT_MAX_CJK_VARIANTS = 200;
    static final int MAX_CJK_QUERY_LENGTH = 64;
    static final int MIN_PREFIX_TOKEN_LENGTH = 3;
    static final int MIN_INITIALS_PREFIX_LENGTH = 2;
    static final int MAX_PINYIN_VARIANTS_PER_SYLLABLE = 4;

    /** 首字母支的整体最小长度：单字母首字母串不生成该支（单字母在真实曲库里过于稠密）。 */
    static final int MIN_INITIALS_BRANCH_LENGTH = 2;

    /** 跨语言释义支的最小 CJK 字数：单字释义极度发散，不生成「汉字 → 英文」候选。 */
    static final int MIN_CROSS_LINGUAL_CJK_LENGTH = 2;

    /** CJK 容错（删除 / 换位）的最小字数：两字删一字会退化成宽覆盖的单字查询。 */
    static final int MIN_CJK_FUZZY_LENGTH = 3;

    /** 拉丁模糊趟的最小词长：过短的词做编辑距离扩展只会引入高频噪声。 */
    static final int MIN_FUZZY_WORD_LENGTH = 3;

    /** 拼音宽松趟每次查询的变体总预算（跨音节累计）。 */
    static final int DEFAULT_MAX_PINYIN_VARIANTS = 16;

    /** 读音支的脚本门控：CJK 输入不生成拼音 / 首字母支，除非 CJK 字数达到下限。 */
    static final boolean REQUIRE_LATIN_FOR_READING_BRANCH = true;
    static final int READING_BRANCH_MIN_CJK_LENGTH = 2;

    /** 末词前缀匹配同时保留精确词，让 bm25 把字面命中排前（精确 ⊂ 前缀，不改变命中集合）。 */
    static final boolean PAIR_EXACT_WITH_PREFIX = true;

    /** 多词查询命中不足时允许丢词降级（仍是字面匹配，方向最安全）。 */
    static final boolean ALLOW_DROP_TOKENS = true;

    /** 单字查询限定在可见文本列，不扫歌词与派生转写列。 */
    static final boolean REQUIRE_METADATA_FOR_SINGLE_CHAR = true;

    /** 首字符不同的替换变体不生成（编辑距离只有 ≤1 一档，首错等价于 2 错）。 */
    static final boolean FIRST_CHAR_TYPO_COSTS_TWO = true;

    private static final Map<Character, String> SUBSTITUTION_ALTERNATIVES = new HashMap<>();

    static {
        put('a', "eiou");
        put('e', "aiou");
        put('i', "aeou");
        put('o', "aeiu");
        put('u', "aeio");
        put('b', "pv");
        put('p', "b");
        put('v', "bf");
        put('f', "v");
        put('d', "t");
        put('t', "d");
        put('c', "ksz");
        put('k', "csq");
        put('s', "cz");
        put('z', "s");
        put('g', "j");
        put('j', "g");
        put('m', "n");
        put('n', "m");
        put('l', "r");
        put('r', "l");
        put('q', "k");
        put('w', "v");
    }

    private static void put(char key, String value) {
        SUBSTITUTION_ALTERNATIVES.put(key, value);
    }

    /** 声母模糊音对（双向）：平翘舌、n/l、f/h。 */
    private static final String[][] PINYIN_INITIAL_FUZZ = {
            {"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"}, {"f", "h"},
    };

    /** 韵母模糊音对（双向，仅匹配词尾）：前后鼻音。 */
    private static final String[][] PINYIN_FINAL_FUZZ = {
            {"ang", "an"}, {"eng", "en"}, {"ing", "in"},
    };

    private static final int MAX_EXACT_TERMS_PER_VARIANT = 32;

    /** 转义为 FTS5 双引号字符串（内部 {@code "} 翻倍）。 */
    static String quote(String token) {
        return "\"" + token.replace("\"", "\"\"") + "\"";
    }

    /** 编译主趟表达式；无可匹配内容返回 {@code null}。 */
    static String compileMatch(Analyzer.QueryTokens tokens, boolean metadata, boolean includeCrossLingual) {
        List<String> branches = new ArrayList<>();
        add(branches, lexicalBranch(tokens.lexical()));

        if (allowReadingBranch(tokens)) {
            // 拉丁单字是真实的首字母检索，保留首字母支；但拼音支对单字母毫无区分力，排除。
            if (!isLatinSingleChar(tokens)) {
                add(branches, pinyinBranch(tokens.pinyin()));
            }
            add(branches, initialsBranch(tokens.pinyinInitials()));
        }
        if (includeCrossLingual) {
            add(branches, crossLingualBranch(tokens.crossLingual(), tokens.lexical()));
        }

        if (restrictSingleCharToMetadata(tokens)) {
            if (branches.isEmpty()) {
                return null;
            }
            String joined = String.join(" OR ", branches);
            String columns = tokens.hasCjk() ? VISIBLE_TEXT_COLUMNS : SINGLE_CHAR_LATIN_COLUMNS;
            return "{" + columns + "} : (" + joined + ")";
        }
        return joinBranches(branches, metadata);
    }

    /** 跨语言候选分支：候选逐字加引号后 OR。 */
    static String compileCrossLingual(Analyzer.QueryTokens tokens, boolean metadata) {
        String branch = crossLingualBranch(tokens.crossLingual(), tokens.lexical());
        if (branch == null) {
            return null;
        }
        return metadata ? "{" + METADATA_COLUMNS + "} : " + branch : branch;
    }

    /**
     * 拼音宽松兜底支：音节位置无关 AND，末音节 ≥3 字符前缀；&lt;2 音节返回 {@code null}。
     */
    static String compilePinyinLoose(Analyzer.QueryTokens tokens, boolean metadata) {
        List<String> clean = clean(tokens.pinyin());
        if (clean.size() < 2) {
            return null;
        }

        // 总变体预算从尾部音节开始分配（尾部通常是查询里最不确定的部分），
        // 预算耗尽后剩余音节退回纯原形，避免超长输入的组合爆炸。
        int budget = Math.max(1, DEFAULT_MAX_PINYIN_VARIANTS);
        List<List<String>> variantsPerSyllable = new ArrayList<>(clean.size());
        for (int i = 0; i < clean.size(); i++) {
            variantsPerSyllable.add(null);
        }
        for (int i = clean.size() - 1; i >= 0; i--) {
            int take = Math.min(MAX_PINYIN_VARIANTS_PER_SYLLABLE, budget);
            List<String> variants = pinyinFuzzyVariants(clean.get(i), take);
            variantsPerSyllable.set(i, variants);
            budget = Math.max(0, budget - variants.size());
        }

        List<String> chunks = new ArrayList<>(clean.size());
        for (int i = 0; i < clean.size(); i++) {
            String syllable = clean.get(i);
            boolean prefix = i == clean.size() - 1 && syllable.length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> variants = variantsPerSyllable.get(i);
            if (variants.size() == 1) {
                chunks.add(prefix ? quote(variants.get(0)) + "*" : quote(variants.get(0)));
                continue;
            }
            StringBuilder group = new StringBuilder();
            for (String variant : variants) {
                if (group.length() > 0) {
                    group.append(" OR ");
                }
                group.append(prefix ? quote(variant) + "*" : quote(variant));
            }
            chunks.add("(" + group + ")");
        }

        String body = String.join(" AND ", chunks);
        return metadata
                ? "{" + METADATA_COLUMNS + "} : (" + body + ")"
                : "{" + PINYIN_COLUMNS + "} : (" + body + ")";
    }

    private static List<String> pinyinFuzzyVariants(String syllable, int maxVariants) {
        List<String> output = new ArrayList<>();
        output.add(syllable);
        if (maxVariants <= 1 || !PinyinSegmenter.isSyllable(syllable)) {
            return output;
        }

        String initial = swapAffix(syllable, PINYIN_INITIAL_FUZZ, true);
        String fin = swapAffix(syllable, PINYIN_FINAL_FUZZ, false);

        addVariant(output, initial, maxVariants);
        addVariant(output, fin, maxVariants);
        if (initial != null) {
            addVariant(output, swapAffix(initial, PINYIN_FINAL_FUZZ, false), maxVariants);
        }
        return output;
    }

    private static void addVariant(List<String> output, String candidate, int maxVariants) {
        if (candidate == null || candidate.isEmpty() || output.size() >= maxVariants) {
            return;
        }
        if (!output.contains(candidate)) {
            output.add(candidate);
        }
    }

    private static String swapAffix(String text, String[][] pairs, boolean prefix) {
        for (String[] pair : pairs) {
            String a = pair[0];
            String b = pair[1];
            if (prefix) {
                if (text.startsWith(a)) {
                    return b + text.substring(a.length());
                }
                if (text.startsWith(b)) {
                    return a + text.substring(b.length());
                }
            } else {
                if (text.endsWith(a)) {
                    return text.substring(0, text.length() - a.length()) + b;
                }
                if (text.endsWith(b)) {
                    return text.substring(0, text.length() - b.length()) + a;
                }
            }
        }
        return null;
    }

    /** 日文读音兜底支：拉丁 / 数字词元的字母 bigram 短语 AND 到读音列。 */
    static String compileReading(Analyzer.QueryTokens tokens, boolean metadata) {
        List<String> parts = new ArrayList<>();
        for (String token : tokens.lexical()) {
            List<String[]> sequences = readingSequencesOf(token);
            if (sequences.isEmpty()) {
                continue;
            }
            List<String> phrases = new ArrayList<>(sequences.size());
            for (String[] sequence : sequences) {
                phrases.add(quote(String.join(" ", sequence)));
            }
            parts.add(phrases.size() == 1 ? phrases.get(0) : "(" + String.join(" OR ", phrases) + ")");
        }
        if (parts.isEmpty()) {
            return null;
        }
        String body = String.join(" AND ", parts);
        return metadata
                ? "{" + METADATA_COLUMNS + "} : (" + body + ")"
                : "{" + READING_COLUMNS + "} : (" + body + ")";
    }

    /** 查询中可走读音趟的词元的规范形 bigram 序列（供 ReadingRanker 分档）。 */
    static List<String[]> readingSequences(Analyzer.QueryTokens tokens) {
        List<String[]> output = new ArrayList<>();
        for (String token : tokens.lexical()) {
            addReadingSequences(token, output);
        }
        return output;
    }

    private static List<String[]> readingSequencesOf(String token) {
        List<String[]> output = new ArrayList<>();
        addReadingSequences(token, output);
        return output;
    }

    private static void addReadingSequences(String token, List<String[]> output) {
        String lower = token.toLowerCase(java.util.Locale.ROOT);
        if (lower.length() < 2 || hasIndexedScript(lower)) {
            return;
        }
        for (String variant : RomajiCanonical.variants(lower)) {
            if (variant.length() < 2) {
                continue;
            }
            String[] bigrams = new String[variant.length() - 1];
            for (int i = 0; i + 1 < variant.length(); i++) {
                bigrams[i] = variant.substring(i, i + 2);
            }
            if (bigrams.length > 0) {
                output.add(bigrams);
            }
        }
    }

    static boolean hasFuzzyEligible(Analyzer.QueryTokens tokens) {
        for (String token : tokens.lexical()) {
            if (isFuzzyEligible(token)) {
                return true;
            }
        }
        return false;
    }

    static String compileFuzzy(Analyzer.QueryTokens tokens, boolean metadata, int maxVariants) {
        return compileFuzzy(tokens, metadata, null, maxVariants);
    }

    static String compileFuzzy(Analyzer.QueryTokens tokens, boolean metadata,
                               List<String> vocabulary, int maxVariants) {
        List<String> branches = new ArrayList<>();
        add(branches, fuzzyLexicalBranch(tokens.lexical(), maxVariants, vocabulary));
        add(branches, pinyinBranch(tokens.pinyin()));
        add(branches, initialsBranch(tokens.pinyinInitials()));
        return joinBranches(branches, metadata);
    }

    /** 生成 CJK 编辑距离 ≤1 变体（删除任一字符 / 相邻换位），不含原串，去重保序。 */
    static List<String> cjkVariants(String query, int maxVariants) {
        String sequence = cjkSubsequence(query);
        if (sequence.length() < MIN_CJK_FUZZY_LENGTH || sequence.length() > MAX_CJK_QUERY_LENGTH) {
            return new ArrayList<>();
        }

        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(sequence);

        // 删除：去掉任一字符（用户多打一字）。
        for (int i = 0; i < sequence.length(); i++) {
            addCandidate(output, seen, sequence.substring(0, i) + sequence.substring(i + 1));
        }
        // 相邻换位（跳过相同字符对）。
        for (int i = 0; i + 1 < sequence.length(); i++) {
            if (sequence.charAt(i) == sequence.charAt(i + 1)) {
                continue;
            }
            char[] chars = sequence.toCharArray();
            char tmp = chars[i];
            chars[i] = chars[i + 1];
            chars[i + 1] = tmp;
            addCandidate(output, seen, new String(chars));
        }

        if (output.size() > maxVariants) {
            return new ArrayList<>(output.subList(0, maxVariants));
        }
        return output;
    }

    private static void addCandidate(List<String> output, Set<String> seen, String candidate) {
        if (!candidate.isEmpty() && seen.add(candidate)) {
            output.add(candidate);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 分支                                                                */
    /* ------------------------------------------------------------------ */

    private static String lexicalBranch(List<String> terms) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }

        List<String> chunks = new ArrayList<>();
        // 每个 chunk 对应的独立词元；短语块为 null，不能做前缀改写。
        List<String> chunkTerms = new ArrayList<>();
        int i = 0;
        while (i < clean.size()) {
            if (isCjkBigram(clean.get(i))) {
                int j = i;
                while (j < clean.size() && isCjkBigram(clean.get(j))) {
                    j++;
                }
                List<String> run = clean.subList(i, j);
                chunks.add(quote(run.size() >= 2 ? String.join(" ", run) : run.get(0)));
                chunkTerms.add(run.size() >= 2 ? null : run.get(0));
                i = j;
            } else {
                chunks.add(quote(clean.get(i)));
                chunkTerms.add(clean.get(i));
                i++;
            }
        }

        String last = clean.get(clean.size() - 1);
        if (isPrefixable(last) && last.length() >= MIN_PREFIX_TOKEN_LENGTH) {
            chunks.set(chunks.size() - 1, prefixTerm(last));
        }

        // 索引里已无「中间字」的 unigram，单字必须改走前缀 `字*`，
        // 才能命中以该字开头的 bigram。只改独立成块的单字，短语块保持原样。
        for (int k = 0; k < chunks.size(); k++) {
            String term = chunkTerms.get(k);
            if (term == null || !isSingleCjkChar(term)) {
                continue;
            }
            chunks.set(k, prefixTerm(term));
        }
        return String.join(" AND ", chunks);
    }

    private static String pinyinBranch(List<String> terms) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.size() == 1) {
            String last = clean.get(0);
            return "{" + PINYIN_COLUMNS + "} : " + quotedAnd(clean, last.length() >= MIN_PREFIX_TOKEN_LENGTH);
        }
        return "{" + PINYIN_COLUMNS + "} : " + quote(String.join(" ", clean));
    }

    private static String initialsBranch(String initials) {
        if (initials == null || initials.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String part : initials.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                terms.add(part);
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        // 单字母首字母串不生成该支：单字母 token 在歌词 / 文件名 / 单字标题里密集存在，
        // 按精确 term 匹配会命中整库。
        int totalLength = 0;
        for (String term : terms) {
            totalLength += term.length();
        }
        if (totalLength < MIN_INITIALS_BRANCH_LENGTH) {
            return null;
        }
        boolean prefixLast = terms.get(terms.size() - 1).length() >= MIN_INITIALS_PREFIX_LENGTH;
        String body = quotedAnd(terms, prefixLast);
        return "({" + INITIALS_COLUMNS + "} : " + body + ")";
    }

    /**
     * 跨语言候选分支。查询只含一个汉字时，丢掉「汉字 → 英文释义」方向的候选：
     * 单字释义极度发散，会把这个查询放大到近乎整库；「拉丁词 → 候选汉字」方向不受影响。
     */
    private static String crossLingualBranch(List<String> terms, List<String> lexical) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }
        int cjkChars = cjkCharCount(lexical);
        if (cjkChars > 0 && cjkChars < MIN_CROSS_LINGUAL_CJK_LENGTH) {
            List<String> filtered = new ArrayList<>(clean.size());
            for (String term : clean) {
                if (isCjkText(term)) {
                    filtered.add(term);
                }
            }
            clean = filtered;
            if (clean.isEmpty()) {
                return null;
            }
        }
        if (clean.size() == 1) {
            return quote(clean.get(0));
        }
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < clean.size(); i++) {
            if (i > 0) {
                builder.append(" OR ");
            }
            builder.append(quote(clean.get(i)));
        }
        return builder.append(")").toString();
    }

    /* ------------------------------------------------------------------ */
    /* 丢词降级                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 丢掉第 {@code dropIndex} 个词后的 MATCH 表达式；不适用时返回 {@code null}。
     */
    static String compileDropTokens(Analyzer.QueryTokens tokens, String query,
                                    boolean metadata, int dropIndex) {
        if (!ALLOW_DROP_TOKENS) {
            return null;
        }
        List<String> words = splitQueryWords(query);
        if (words.size() < 2 || dropIndex < 0 || dropIndex >= words.size()) {
            return null;
        }

        List<String> keptWords = new ArrayList<>(words.size() - 1);
        for (int i = 0; i < words.size(); i++) {
            if (i != dropIndex) {
                keptWords.add(words.get(i));
            }
        }
        if (keptWords.isEmpty()) {
            return null;
        }

        Analyzer.QueryTokens keptTokens = Analyzer.analyzeQuery(String.join(" ", keptWords));
        if (keptTokens.lexical().isEmpty()) {
            return null;
        }

        // 只重新编译「词法支 + 读音支」，不含跨语言 / 容错：
        // 本趟的语义是「字面少匹配一个词」，不是放宽匹配维度。
        String expression = compileMatch(keptTokens, metadata, false);
        if (expression == null) {
            return null;
        }
        String original = compileMatch(tokens, metadata, false);
        return expression.equals(original) ? null : expression;
    }

    /**
     * 丢词降级的候选丢法，按优先级排序。
     *
     * <p>中文音乐检索的主流写法是「歌手 + 歌名」，歌名在尾部且最该保留，
     * 因此含 CJK 的查询先丢首位词；纯拉丁查询仍按尾部优先。
     */
    static List<Integer> dropTokenCandidates(Analyzer.QueryTokens tokens, String query) {
        List<String> words = splitQueryWords(query);
        if (words.size() < 2) {
            return List.of();
        }
        List<Integer> indices = new ArrayList<>(words.size());
        if (tokens.hasCjk()) {
            for (int i = 0; i < words.size(); i++) {
                indices.add(i);
            }
        } else {
            for (int i = words.size() - 1; i >= 0; i--) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * 按「词在 title / artist 列的命中分布」重排丢词候选：
     * 越像歌名的词越晚丢，越像歌手的词越早丢；信息不足时保持原顺序。
     */
    static List<Integer> orderDropsByFieldAffinity(List<Integer> candidates,
                                                   List<Integer> titleCounts,
                                                   List<Integer> artistCounts) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<Integer> ordered = new ArrayList<>(candidates);
        Map<Integer, Integer> positions = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            positions.put(ordered.get(i), i);
        }
        ordered.sort((a, b) -> {
            int cmp = Integer.compare(affinity(a, titleCounts, artistCounts),
                    affinity(b, titleCounts, artistCounts));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(positions.get(a), positions.get(b));
        });
        return ordered;
    }

    private static int affinity(int index, List<Integer> titleCounts, List<Integer> artistCounts) {
        if (index < 0 || index >= titleCounts.size() || index >= artistCounts.size()) {
            return 0;
        }
        return titleCounts.get(index) - artistCounts.get(index);
    }

    private static List<String> splitQueryWords(String query) {
        List<String> words = new ArrayList<>();
        if (query == null) {
            return words;
        }
        for (String part : query.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                words.add(part);
            }
        }
        return words;
    }

    /** 查询侧 CJK 词法词元里去重后的汉字个数（bigram 会重复计数，故按字符集去重）。 */
    private static int cjkCharCount(List<String> lexical) {
        if (lexical == null || lexical.isEmpty()) {
            return 0;
        }
        Set<Character> chars = new HashSet<>();
        for (String token : lexical) {
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (ScriptRanges.isCjk(ch)) {
                    chars.add(ch);
                }
            }
        }
        return chars.size();
    }

    /** 整串是否只由 CJK / 假名 / 谚文构成（跨语言候选归属判定用）。 */
    private static boolean isCjkText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isIndexedScript(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 词法词元里去重后的可见字符数（空白不计）。 */
    private static int visibleCharCount(List<String> lexical) {
        if (lexical == null || lexical.isEmpty()) {
            return 0;
        }
        Set<Character> chars = new HashSet<>();
        for (String token : lexical) {
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (!Character.isWhitespace(ch)) {
                    chars.add(ch);
                }
            }
        }
        return chars.size();
    }

    /** 单字查询的列门控：只在查询恰好含一个可见字符时生效。 */
    private static boolean restrictSingleCharToMetadata(Analyzer.QueryTokens tokens) {
        return REQUIRE_METADATA_FOR_SINGLE_CHAR && visibleCharCount(tokens.lexical()) == 1;
    }

    /** 查询是否为「单个拉丁字母」（真实的首字母检索）。 */
    private static boolean isLatinSingleChar(Analyzer.QueryTokens tokens) {
        if (visibleCharCount(tokens.lexical()) != 1) {
            return false;
        }
        for (String token : tokens.lexical()) {
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (Character.isWhitespace(ch)) {
                    continue;
                }
                return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
            }
        }
        return false;
    }

    /**
     * 读音支（拼音 / 首字母）是否允许进入主趟：CJK 输入产出的拼音是派生的，
     * 与检索意图无关；只有拉丁输入或 CJK 字数达标时才允许。
     */
    private static boolean allowReadingBranch(Analyzer.QueryTokens tokens) {
        if (!REQUIRE_LATIN_FOR_READING_BRANCH) {
            return true;
        }
        if (!tokens.hasCjk()) {
            return true;
        }
        return cjkCharCount(tokens.lexical()) >= READING_BRANCH_MIN_CJK_LENGTH;
    }

    /** 词元是否为单个 CJK 字符（这类词元在索引里不一定有独立 unigram，须走前缀）。 */
    private static boolean isSingleCjkChar(String token) {
        if (token.codePointCount(0, token.length()) != 1) {
            return false;
        }
        char ch = token.charAt(0);
        return isIndexedScript(ch);
    }

    /** 末词编译为 `("tok" OR "tok"*)`，或纯前缀 `"tok"*`。 */
    private static String prefixTerm(String token) {
        return PAIR_EXACT_WITH_PREFIX
                ? "(" + quote(token) + " OR " + quote(token) + "*)"
                : quote(token) + "*";
    }

    private static String fuzzyLexicalBranch(List<String> terms, int maxVariants, List<String> vocabulary) {
        List<String> clean = clean(terms);
        if (clean.isEmpty()) {
            return null;
        }

        boolean useVocabulary = vocabulary != null && !vocabulary.isEmpty();
        List<Integer> expandable = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) {
            if (isFuzzyEligible(clean.get(i))) {
                expandable.add(i);
            }
        }

        Map<Integer, List<String>> groups = new HashMap<>();
        for (int index : expandable) {
            boolean prefix = index == clean.size() - 1
                    && isPrefixable(clean.get(index))
                    && clean.get(index).length() >= MIN_PREFIX_TOKEN_LENGTH;
            if (!useVocabulary) {
                List<String> expressions = new ArrayList<>();
                for (String variant : editDistanceOneVariants(clean.get(index))) {
                    expressions.add(prefix ? quote(variant) + "*" : quote(variant));
                }
                groups.put(index, expressions);
                continue;
            }
            groups.put(index, vocabularyExpressions(clean.get(index), vocabulary, maxVariants));
        }

        int total = 0;
        for (List<String> list : groups.values()) {
            total += list.size();
        }
        if (!useVocabulary && total > maxVariants && !expandable.isEmpty()) {
            int last = expandable.get(expandable.size() - 1);
            boolean lastPrefix = last == clean.size() - 1
                    && isPrefixable(clean.get(last))
                    && clean.get(last).length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> full = new ArrayList<>();
            for (String variant : editDistanceOneVariants(clean.get(last))) {
                full.add(lastPrefix ? quote(variant) + "*" : quote(variant));
            }
            groups.clear();
            groups.put(last, full.size() > maxVariants ? new ArrayList<>(full.subList(0, maxVariants)) : full);
        }

        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < clean.size()) {
            if (isCjkBigram(clean.get(index))) {
                int j = index;
                while (j < clean.size() && isCjkBigram(clean.get(j))) {
                    j++;
                }
                List<String> run = clean.subList(index, j);
                chunks.add(quote(run.size() >= 2 ? String.join(" ", run) : run.get(0)));
                index = j;
                continue;
            }

            boolean prefix = index == clean.size() - 1
                    && isPrefixable(clean.get(index))
                    && clean.get(index).length() >= MIN_PREFIX_TOKEN_LENGTH;
            List<String> list = groups.get(index);
            if (list != null && !list.isEmpty()) {
                chunks.add(list.size() == 1 ? list.get(0) : "(" + String.join(" OR ", list) + ")");
            } else if (useVocabulary && isFuzzyEligible(clean.get(index))) {
                List<String> bare = new ArrayList<>();
                for (String variant : editDistanceOneVariants(clean.get(index))) {
                    bare.add(prefix ? quote(variant) + "*" : quote(variant));
                }
                if (bare.size() > maxVariants) {
                    bare = new ArrayList<>(bare.subList(0, maxVariants));
                }
                chunks.add(bare.size() == 1 ? bare.get(0) : "(" + String.join(" OR ", bare) + ")");
            } else {
                String quoted = quote(clean.get(index));
                chunks.add(prefix ? quoted + "*" : quoted);
            }
            index++;
        }
        return String.join(" AND ", chunks);
    }

    private static List<String> vocabularyExpressions(String word, List<String> vocabulary, int maxVariants) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String variant : editDistanceOneVariants(word)) {
            FuzzyVocabulary.Range range = FuzzyVocabulary.prefixRange(vocabulary, variant);
            if (range.isEmpty()) {
                continue;
            }

            if (range.end() - range.start() <= MAX_EXACT_TERMS_PER_VARIANT) {
                for (int i = range.start(); i < range.end() && output.size() < maxVariants; i++) {
                    String term = vocabulary.get(i);
                    if (!FuzzyVocabulary.withinOne(word, term)) {
                        continue;
                    }
                    if (seen.add(term)) {
                        output.add(quote(term));
                    }
                }
                continue;
            }

            String expression = quote(variant) + "*";
            if (seen.add(expression) && output.size() < maxVariants) {
                output.add(expression);
            }
        }

        return output;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private static String joinBranches(List<String> branches, boolean metadata) {
        if (branches.isEmpty()) {
            return null;
        }
        String joined = String.join(" OR ", branches);
        if (metadata) {
            return "{" + METADATA_COLUMNS + "} : (" + joined + ")";
        }
        return branches.size() == 1 ? joined : "(" + joined + ")";
    }

    private static void add(List<String> branches, String branch) {
        if (branch != null) {
            branches.add(branch);
        }
    }

    private static List<String> clean(List<String> terms) {
        List<String> clean = new ArrayList<>(terms.size());
        for (String term : terms) {
            if (term == null) {
                continue;
            }
            String trimmed = term.trim();
            if (!trimmed.isEmpty()) {
                clean.add(trimmed);
            }
        }
        return clean;
    }

    private static String quotedAnd(List<String> terms, boolean prefixLast) {
        List<String> parts = new ArrayList<>(terms.size());
        for (int i = 0; i < terms.size(); i++) {
            boolean prefixed = i == terms.size() - 1 && prefixLast;
            parts.add(prefixed ? prefixTerm(terms.get(i)) : quote(terms.get(i)));
        }
        return String.join(" AND ", parts);
    }

    /** 生成编辑距离 ≤1 变体（含原词；去重保序）。 */
    private static List<String> editDistanceOneVariants(String word) {
        List<String> output = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        output.add(word);
        seen.add(word);

        for (int i = 0; i < word.length(); i++) {
            addVariantSet(output, seen, word.substring(0, i) + word.substring(i + 1));
        }
        for (int i = 0; i + 1 < word.length(); i++) {
            if (word.charAt(i) == word.charAt(i + 1)) {
                continue;
            }
            char[] chars = word.toCharArray();
            char tmp = chars[i];
            chars[i] = chars[i + 1];
            chars[i + 1] = tmp;
            addVariantSet(output, seen, new String(chars));
        }
        // 首字符不生成替换变体：编辑距离只有 ≤1 一档，首字符不同往往意味着另一个词。
        for (int i = FIRST_CHAR_TYPO_COSTS_TWO ? 1 : 0; i < word.length(); i++) {
            String alternatives = SUBSTITUTION_ALTERNATIVES.get(word.charAt(i));
            if (alternatives == null) {
                continue;
            }
            for (int k = 0; k < alternatives.length(); k++) {
                addVariantSet(output, seen,
                        word.substring(0, i) + alternatives.charAt(k) + word.substring(i + 1));
            }
        }
        return output;
    }

    private static void addVariantSet(List<String> output, Set<String> seen, String candidate) {
        if (!candidate.isEmpty() && seen.add(candidate)) {
            output.add(candidate);
        }
    }

    private static boolean isCjkBigram(String token) {
        int count = token.codePointCount(0, token.length());
        if (count != 2) {
            return false;
        }
        return token.codePoints().allMatch(cp -> cp <= Character.MAX_VALUE && isIndexedScript((char) cp));
    }

    private static boolean isPrefixable(String token) {
        return token.codePoints().noneMatch(cp -> cp <= Character.MAX_VALUE && isIndexedScript((char) cp));
    }

    private static boolean isFuzzyEligible(String token) {
        if (token.length() < MIN_FUZZY_WORD_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIndexedScript(char c) {
        return ScriptRanges.isCjk(c) || ScriptRanges.isSubstringScript(c);
    }

    private static boolean hasIndexedScript(String token) {
        return token.codePoints().anyMatch(cp -> cp <= Character.MAX_VALUE && isIndexedScript((char) cp));
    }

    private static String cjkSubsequence(String query) {
        StringBuilder builder = new StringBuilder(query.length());
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (isIndexedScript(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
