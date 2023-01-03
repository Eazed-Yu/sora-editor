/*
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2023  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.widget;

import android.app.ProgressDialog;
import android.widget.Toast;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import io.github.rosemoe.sora.R;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.util.LongArrayList;

/**
 * Search text in editor
 *
 * @author Rosemoe
 */
public class EditorSearcher {

    private final CodeEditor editor;
    protected String currentPattern;
    protected SearchOptions searchOptions;
    protected Thread currentThread;
    protected volatile LongArrayList lastResults;

    EditorSearcher(@NonNull CodeEditor editor) {
        this.editor = editor;
        this.editor.subscribeEvent(ContentChangeEvent.class, ((event, unsubscribe) -> {
            if (hasQuery()) {
                executeMatch();
            }
        }));
    }

    public void search(@NonNull String pattern, @NonNull SearchOptions options) {
        if (pattern.length() == 0) {
            throw new IllegalArgumentException("pattern length must be > 0");
        }
        if (options.type == SearchOptions.TYPE_REGULAR_EXPRESSION) {
            // Pre-check
            //noinspection ResultOfMethodCallIgnored
            Pattern.compile(pattern);
        }
        currentPattern = pattern;
        searchOptions = options;
        executeMatch();
        editor.postInvalidate();
    }

    private void executeMatch() {
        if (currentThread != null && currentThread.isAlive()) {
            currentThread.interrupt();
        }
        var runnable = new SearchRunnable(editor.getText(), searchOptions, currentPattern);
        currentThread = new Thread(runnable);
        currentThread.start();
    }

    public void stopSearch() {
        if (currentThread != null && currentThread.isAlive()) {
            currentThread.interrupt();
        }
        currentThread = null;
        lastResults = null;
        currentPattern = null;
        searchOptions = null;
    }

    public boolean hasQuery() {
        return currentPattern != null;
    }

    private void checkState() {
        if (!hasQuery()) {
            throw new IllegalStateException("pattern not set");
        }
    }

    public int getCurrentMatchedPositionIndex() {
        checkState();
        var cur = editor.getCursor();
        if (!cur.isSelected()) {
            return -1;
        }
        var left = cur.getLeft();
        var right = cur.getRight();

        if (isResultValid()) {
            var res = lastResults;
            if (res == null) {
                return -1;
            }
            var packed = IntPair.pack(left, right);
            for (int i = 0; i < res.size(); i++) {
                var value = res.get(i);
                if (value == packed) {
                    return i;
                } else if (value > packed) {
                    // Values behind can not be valid
                    break;
                }
            }
        }
        return -1;
    }

    public int getMatchedPositionCount() {
        checkState();
        if (!isResultValid()) {
            return 0;
        }
        var result = lastResults;
        return result == null ? 0 : result.size();
    }

    public boolean gotoNext() {
        checkState();
        if (isResultValid()) {
            var res = lastResults;
            if (res == null) {
                return false;
            }
            var right = editor.getCursor().getRight();
            for (int i = 0; i < res.size(); i++) {
                var data = res.get(i);
                var start = IntPair.getFirst(data);
                if (start >= right) {
                    var pos1 = editor.getText().getIndexer().getCharPosition(start);
                    var pos2 = editor.getText().getIndexer().getCharPosition(IntPair.getSecond(data));
                    editor.setSelectionRegion(pos1.line, pos1.column, pos2.line, pos2.column, SelectionChangeEvent.CAUSE_SEARCH);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean gotoPrevious() {
        checkState();
        if (isResultValid()) {
            var res = lastResults;
            if (res == null) {
                return false;
            }
            var left = editor.getCursor().getLeft();
            for (int i = res.size() - 1; i >= 0; i--) {
                var data = res.get(i);
                var end = IntPair.getSecond(data);
                if (end <= left) {
                    var pos1 = editor.getText().getIndexer().getCharPosition(IntPair.getFirst(data));
                    var pos2 = editor.getText().getIndexer().getCharPosition(end);
                    editor.setSelectionRegion(pos1.line, pos1.column, pos2.line, pos2.column, SelectionChangeEvent.CAUSE_SEARCH);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isMatchedPositionSelected() {
        return getCurrentMatchedPositionIndex() > -1;
    }

    public void replaceThis(@NonNull String replacement) {
        if (!editor.isEditable()) {
            return;
        }
        if (isMatchedPositionSelected()) {
            editor.commitText(replacement);
        } else {
            gotoNext();
        }
    }

    public void replaceAll(@NonNull String replacement) {
        replaceAll(replacement, null);
    }

    public void replaceAll(@NonNull String replacement, @Nullable final Runnable whenSucceeded) {
        if (!editor.isEditable()) {
            return;
        }
        checkState();
        if (!isResultValid()) {
            Toast.makeText(editor.getContext(), R.string.editor_search_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        var context = editor.getContext();
        final var dialog = ProgressDialog.show(context, context.getString(R.string.replaceAll), context.getString(R.string.editor_search_replacing), true, false);
        final var res = lastResults;
        new Thread(() -> {
            try {
                var sb = editor.getText().toStringBuilder();
                int newLength = replacement.length();
                int delta = 0;
                for (int i = 0; i < res.size(); i++) {
                    var region = res.get(i);
                    var start = IntPair.getFirst(region);
                    var end = IntPair.getSecond(region);
                    var oldLength = end - start;
                    sb.replace(start + delta, end + delta, replacement);
                    delta += newLength - oldLength;
                }
                editor.postInLifecycle(() -> {
                    var pos = editor.getCursor().left();
                    editor.getText().replace(0, 0, editor.getLineCount() - 1, editor.getText().getColumnCount(editor.getLineCount() - 1), sb);
                    editor.setSelectionAround(pos.line, pos.column);
                    dialog.dismiss();

                    if (whenSucceeded != null) {
                        whenSucceeded.run();
                    }
                });
            } catch (Exception e) {
                editor.postInLifecycle(() -> {
                    Toast.makeText(editor.getContext(), "Replace failed:" + e, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            }
        }).start();
    }

    protected boolean isResultValid() {
        return currentThread == null || !currentThread.isAlive();
    }

    public static class SearchOptions {

        public final boolean ignoreCase;
        @IntRange(from = 1, to = 3)
        public final int type;
        /**
         * Normal text searching
         */
        public final static int TYPE_NORMAL = 1;
        /**
         * Text searching by whole word
         */
        public final static int TYPE_WHOLE_WORD = 2;
        /**
         * Use regular expression for text searching
         */
        public final static int TYPE_REGULAR_EXPRESSION = 3;

        public SearchOptions(boolean ignoreCase, boolean useRegex) {
            this(useRegex ? TYPE_REGULAR_EXPRESSION : TYPE_NORMAL, ignoreCase);
        }

        public SearchOptions(@IntRange(from = 1, to = 3) int type, boolean ignoreCase) {
            if (type < 1 || type > 3) {
                throw new IllegalArgumentException("invalid type");
            }
            this.type = type;
            this.ignoreCase = ignoreCase;
        }

    }

    /**
     * Run for regex matching
     */
    private final class SearchRunnable implements Runnable {

        private final StringBuilder text;
        private final String pattern;
        private final SearchOptions options;
        private Thread localThread;

        public SearchRunnable(@NonNull Content content, @NonNull SearchOptions options, @NonNull String pattern) {
            this.text = content.toStringBuilder();
            this.options = options;
            this.pattern = pattern;
        }

        private boolean checkNotCancelled() {
            return currentThread == localThread && !Thread.interrupted();
        }

        @Override
        public void run() {
            localThread = Thread.currentThread();
            var results = new LongArrayList();
            var textLength = text.length();
            var ignoreCase = searchOptions.ignoreCase;
            var pattern = this.pattern;
            switch (options.type) {
                case SearchOptions.TYPE_NORMAL: {
                    int nextStart = 0;
                    var patternLength = pattern.length();
                    while (nextStart != -1 && nextStart < textLength) {
                        nextStart = TextUtils.indexOf(text, pattern, ignoreCase, nextStart);
                        if (nextStart != -1) {
                            results.add(IntPair.pack(nextStart, nextStart + patternLength));
                            nextStart += patternLength;
                        }
                    }
                    break;
                }
                case SearchOptions.TYPE_WHOLE_WORD:
                    pattern = "\\b" + Pattern.quote(pattern) + "\\b";
                    // fall-through
                case SearchOptions.TYPE_REGULAR_EXPRESSION:
                    var regex = Pattern.compile(pattern, (ignoreCase ? Pattern.CASE_INSENSITIVE : 0) | Pattern.MULTILINE);
                    int lastEnd = 0;
                    // Matcher will call toString() on input several times
                    var string = text.toString();
                    var matcher = regex.matcher(string);
                    while (lastEnd < textLength && matcher.find(lastEnd)) {
                        lastEnd = matcher.end();
                        results.add(IntPair.pack(matcher.start(), lastEnd));
                    }
            }
            if (checkNotCancelled()) {
                lastResults = results;
                editor.postInvalidate();
            }
        }
    }

}
