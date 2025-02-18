package com.taosdata.jdbc.utils;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Utils {

    private static Pattern ptn = Pattern.compile(".*?'");

    public static String escapeSingleQuota(String origin) {
        Matcher m = ptn.matcher(origin);
        StringBuffer sb = new StringBuffer();
        int end = 0;
        while (m.find()) {
            end = m.end();
            String seg = origin.substring(m.start(), end);
            int len = seg.length();
            if (len == 1) {
                if ('\'' == seg.charAt(0)) {
                    sb.append("\\'");
                } else {
                    sb.append(seg);
                }
            } else { // len > 1
                sb.append(seg.substring(0, seg.length() - 2));
                char lastcSec = seg.charAt(seg.length() - 2);
                if (lastcSec == '\\') {
                    sb.append("\\'");
                } else {
                    sb.append(lastcSec);
                    sb.append("\\'");
                }
            }
        }

        if (end < origin.length()) {
            sb.append(origin.substring(end));
        }
        return sb.toString();
    }

    public static String getNativeSql(String rawSql, Object[] parameters) {
        // toLowerCase
        String preparedSql = rawSql.trim().toLowerCase();

        String[] clause = new String[0];
        if (SqlSyntaxValidator.isInsertSql(preparedSql)) {
            // insert or import
            clause = new String[]{"values\\s*\\(.*?\\)", "tags\\s*\\(.*?\\)"};
        }
        if (SqlSyntaxValidator.isSelectSql(preparedSql)) {
            // select
            clause = new String[]{"where\\s*.*"};
        }
        Map<Integer, Integer> placeholderPositions = new HashMap<>();
        RangeSet<Integer> clauseRangeSet = TreeRangeSet.create();
        findPlaceholderPosition(preparedSql, placeholderPositions);
        findClauseRangeSet(preparedSql, clause, clauseRangeSet);

        return transformSql(preparedSql, parameters, placeholderPositions, clauseRangeSet);
    }

    private static void findClauseRangeSet(String preparedSql, String[] regexArr, RangeSet<Integer> clauseRangeSet) {
        clauseRangeSet.clear();
        for (String regex : regexArr) {
            Matcher matcher = Pattern.compile(regex).matcher(preparedSql);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                clauseRangeSet.add(Range.closed(start, end));
            }
        }
    }

    private static void findPlaceholderPosition(String preparedSql, Map<Integer, Integer> placeholderPosition) {
        placeholderPosition.clear();
        Matcher matcher = Pattern.compile("\\?").matcher(preparedSql);
        int index = 0;
        while (matcher.find()) {
            int pos = matcher.start();
            placeholderPosition.put(index, pos);
            index++;
        }
    }

    /***
     *
     * @param preparedSql
     * @param paramArr
     * @param placeholderPosition
     * @param clauseRangeSet
     * @return
     */
    private static String transformSql(String preparedSql, Object[] paramArr, Map<Integer, Integer> placeholderPosition, RangeSet<Integer> clauseRangeSet) {
        String[] sqlArr = preparedSql.split("\\?");
        return IntStream.range(0, sqlArr.length).mapToObj(index -> {
            if (index == paramArr.length)
                return sqlArr[index];

            Object para = paramArr[index];
            String paraStr;
            if (para != null) {
                if (para instanceof byte[]) {
                    paraStr = new String((byte[]) para, Charset.forName("UTF-8"));
                } else {
                    paraStr = para.toString();
                }
                // if para is timestamp or String or byte[] need to translate ' character
                if (para instanceof Timestamp || para instanceof String || para instanceof byte[]) {
                    paraStr = Utils.escapeSingleQuota(paraStr);

                    Integer pos = placeholderPosition.get(index);
                    boolean contains = clauseRangeSet.contains(pos);
                    if (contains) {
                        paraStr = "'" + paraStr + "'";
                    }
                }
            } else {
                paraStr = "NULL";
            }
            return sqlArr[index] + paraStr;
        }).collect(Collectors.joining());
    }

}
