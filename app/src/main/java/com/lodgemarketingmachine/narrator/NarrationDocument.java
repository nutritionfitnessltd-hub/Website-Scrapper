package com.lodgemarketingmachine.narrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NarrationDocument {
    private static final Pattern TOP_LEVEL = Pattern.compile(
            "(?m)^#\\s+(Chapter\\s+([0-9]+)[^\\n]*|Before We Begin|How to Use This Book|What You Will Build|The Complete Machine|Acknowledgements|Ideas and Influences|About the Author)\\s*$");

    private NarrationDocument() {}

    static List<Section> parse(String source) {
        String clean = source.replace("\r\n", "\n");
        Matcher matcher = TOP_LEVEL.matcher(clean);
        List<Integer> starts = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            headings.add(matcher.group(1).trim());
        }

        List<Section> sections = new ArrayList<>();
        if (starts.isEmpty()) {
            sections.add(new Section("full_book", "Full Book", clean));
            return sections;
        }

        if (starts.get(0) > 0) {
            String frontMatter = clean.substring(0, starts.get(0)).trim();
            if (!frontMatter.isEmpty()) sections.add(new Section("front_matter", "Front Matter", frontMatter));
        }

        for (int index = 0; index < starts.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : clean.length();
            String block = clean.substring(starts.get(index), end).trim();
            String heading = headings.get(index);
            String title = enrichTitle(heading, block);
            String id = createId(heading);
            sections.add(new Section(id, title, block));
        }
        return sections;
    }

    static List<String> chunks(String markdown) {
        String speechText = toSpeechText(markdown);
        List<String> output = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : speechText.split("\\n\\s*\\n")) {
            String value = paragraph.trim();
            if (value.isEmpty()) continue;
            if (current.length() + value.length() + 2 > 3700 && current.length() > 0) {
                output.add(current.toString());
                current.setLength(0);
            }
            if (value.length() > 3700) {
                for (int offset = 0; offset < value.length(); offset += 3500) {
                    output.add(value.substring(offset, Math.min(value.length(), offset + 3500)));
                }
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(value);
            }
        }
        if (current.length() > 0) output.add(current.toString());
        return output;
    }

    private static String enrichTitle(String heading, String block) {
        Matcher chapterNumber = Pattern.compile("^Chapter\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE).matcher(heading);
        if (chapterNumber.find()) {
            Matcher subheading = Pattern.compile("(?m)^##\\s+([^\\n]+)$").matcher(block);
            if (subheading.find()) {
                return "Chapter " + chapterNumber.group(1) + " — " + cleanInline(subheading.group(1));
            }
        }
        return cleanInline(heading);
    }

    private static String createId(String heading) {
        Matcher number = Pattern.compile("Chapter\\s+([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(heading);
        if (number.find()) {
            return String.format(Locale.UK, "chapter_%02d", Integer.parseInt(number.group(1)));
        }
        return heading.toLowerCase(Locale.UK)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String toSpeechText(String markdown) {
        String value = markdown
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replace("**", "")
                .replace("__", "")
                .replaceAll("(?m)^[-*]\\s+", "• ")
                .replaceAll("\\[(.*?)\\]\\([^)]*\\)", "$1")
                .replaceAll("`([^`]*)`", "$1");
        return value.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String cleanInline(String value) {
        return value.replace("**", "").replace("__", "").trim();
    }

    static final class Section {
        final String id;
        final String title;
        final String text;

        Section(String id, String title, String text) {
            this.id = id;
            this.title = title;
            this.text = text;
        }
    }
}
