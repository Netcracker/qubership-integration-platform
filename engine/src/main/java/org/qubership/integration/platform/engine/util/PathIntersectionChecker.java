package org.qubership.integration.platform.engine.util;

import org.qubership.integration.platform.engine.util.paths.PathElement;
import org.qubership.integration.platform.engine.util.paths.PathParser;
import org.qubership.integration.platform.engine.util.paths.PathPatternCharacters;

import java.util.List;
import java.util.stream.IntStream;

public class PathIntersectionChecker {
    private final PathParser parser;

    public PathIntersectionChecker(PathParser parser) {
        this.parser = parser;
    }

    public PathIntersectionChecker() {
        this(new PathParser());
    }

    public boolean intersects(String path1, String path2) {
        return intersects(parser.parse(path1), parser.parse(path2));
    }

    public boolean intersects(List<PathElement> path1, List<PathElement> path2) {
        return (path1.size() == path2.size())
                && IntStream.range(0, path1.size()).allMatch(index -> intersects(path1.get(index), path2.get(index)));
    }

    public boolean intersects(PathElement element1, PathElement element2) {
        return areValueSetsSpecifiedByPatternsIntersects(element1.getPattern(), 0, element2.getPattern(), 0);
    }

    public boolean areValueSetsSpecifiedByPatternsIntersects(String pattern1, int pos1, String pattern2, int pos2) {
        while ((pos1 < pattern1.length())
                && (pos2 < pattern2.length())
                && (pattern1.charAt(pos1) != PathPatternCharacters.PLACEHOLDER)
                && (pattern1.charAt(pos1) == pattern2.charAt(pos2))) {
            ++pos1;
            ++pos2;
        }

        boolean isEmpty1 = pattern1.length() == pos1;
        boolean isEmpty2 = pattern2.length() == pos2;
        if (isEmpty1 && isEmpty2) {
            return true;
        }
        if (isEmpty1 != isEmpty2) {
            return false;
        }

        char c1 = pattern1.charAt(pos1);
        char c2 = pattern2.charAt(pos2);
        if (c1 == PathPatternCharacters.PLACEHOLDER) {
            return compareTails(pattern1, pos1 + 1, pattern2, pos2 + 1);
        } else if (c2 == PathPatternCharacters.PLACEHOLDER) {
            return compareTails(pattern2, pos2 + 1, pattern1, pos1 + 1);
        } else {
            return false;
        }
    }

    private boolean compareTails(String pattern1, int pos1, String pattern2, int pos2) {
        for (int i = pos2; i <= pattern2.length(); ++i) {
            if (areValueSetsSpecifiedByPatternsIntersects(pattern1, pos1, pattern2, i)) {
                return true;
            }
        }
        return false;
    }
}
