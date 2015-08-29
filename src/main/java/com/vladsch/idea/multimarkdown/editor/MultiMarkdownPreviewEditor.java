/*
 * Copyright (c) 2011-2014 Julien Nicoulaud <julien.nicoulaud@gmail.com>
 * Copyright (c) 2015-2015 Vladimir Schneider <vladimir.schneider@gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.vladsch.idea.multimarkdown.editor;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.lang.Language;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.ui.components.JBScrollPane;

import com.vladsch.idea.multimarkdown.MultiMarkdownBundle;
import com.vladsch.idea.multimarkdown.settings.MultiMarkdownGlobalSettings;
import com.vladsch.idea.multimarkdown.settings.MultiMarkdownGlobalSettingsListener;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pegdown.PegDownProcessor;

import javax.swing.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.Timer;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiMarkdownPreviewEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOGGER = Logger.getInstance(MultiMarkdownPreviewEditor.class);

    public static final String PREVIEW_EDITOR_NAME = MultiMarkdownBundle.message("multimarkdown.preview-tab-name");

    public static final String TEXT_EDITOR_NAME = MultiMarkdownBundle.message("multimarkdown.html-tab-name");

    @NonNls
    public static final String PREVIEW_STYLESHEET_LIGHT = "/com/vladsch/idea/multimarkdown/default.css";

    public static final String PREVIEW_STYLESHEET_DARK = "/com/vladsch/idea/multimarkdown/darcula.css";

    /** The {@link java.awt.Component} used to render the HTML preview. */
    protected final JEditorPane jEditorPane;

    /** The {@link JBScrollPane} allowing to browse {@link #jEditorPane}. */
    protected final JBScrollPane scrollPane;

    /** The {@link Document} previewed in this editor. */
    protected final Document document;
    //private final EditorTextField myTextViewer;
    private final EditorImpl myTextViewer;

    private boolean isReleased = false;

    protected MultiMarkdownGlobalSettingsListener globalSettingsListener;

    /** The {@link PegDownProcessor} used for building the document AST. */
    private ThreadLocal<PegDownProcessor> processor = initProcessor();

    private boolean isActive = false;

    private boolean isRawHtml = false;

    private boolean isEditorTabVisible = true;

    private Project project;

    public static boolean isShowModified() {
        return MultiMarkdownGlobalSettings.getInstance().showHtmlTextAsModified.getValue();
    }

    public static int getParsingTimeout() {
        return MultiMarkdownGlobalSettings.getInstance().parsingTimeout.getValue();
    }

    public static int getUpdateDelay() {
        return MultiMarkdownGlobalSettings.getInstance().updateDelay.getValue();
    }

    public static boolean isTaskLists() {
        return MultiMarkdownGlobalSettings.getInstance().taskLists.getValue();
    }

    public static boolean isIconBullets() {
        return MultiMarkdownGlobalSettings.getInstance().iconBullets.getValue();
    }

    public static String getCustomCss() {
        return MultiMarkdownGlobalSettings.getInstance().customCss.getValue();
    }

    public static boolean isShowHtmlText() {
        return MultiMarkdownGlobalSettings.getInstance().showHtmlText.getValue();
    }

    /** Init/reinit thread local {@link PegDownProcessor}. */
    private static ThreadLocal<PegDownProcessor> initProcessor() {
        return new ThreadLocal<PegDownProcessor>() {
            @Override protected PegDownProcessor initialValue() {
                return new PegDownProcessor(MultiMarkdownGlobalSettings.getInstance().getExtensionsValue(), getParsingTimeout());
            }
        };
    }

    /** Indicates whether the HTML preview is obsolete and should regenerated from the Markdown {@link #document}. */
    protected boolean previewIsObsolete = true;

    protected Timer updateDelayTimer;

    protected void updateEditorTabIsVisible() {
        if (isRawHtml) {
            isEditorTabVisible = isShowHtmlText();
            getComponent().setVisible(isEditorTabVisible);
        } else {
            isEditorTabVisible = true;
        }
    }

    /**
     * Build a new instance of {@link MultiMarkdownPreviewEditor}.
     *
     * @param project  the {@link Project} containing the document
     * @param document the {@link com.intellij.openapi.editor.Document} previewed in this editor.
     */
    public MultiMarkdownPreviewEditor(@NotNull Project project, @NotNull Document document, boolean isRawHtml) {
        this.isRawHtml = isRawHtml;
        this.document = document;
        this.project = project;

        // Listen to the document modifications.
        this.document.addDocumentListener(new DocumentAdapter() {
            @Override
            public void documentChanged(DocumentEvent e) {
                delayedHtmlPreviewUpdate(false);
            }
        });

        // Listen to settings changes
        MultiMarkdownGlobalSettings.getInstance().addListener(globalSettingsListener = new MultiMarkdownGlobalSettingsListener() {
            public void handleSettingsChanged(@NotNull final MultiMarkdownGlobalSettings newSettings) {
                updateEditorTabIsVisible();
                delayedHtmlPreviewUpdate(true);
            }
        });

        if (isRawHtml) {
            jEditorPane = null;
            scrollPane = null;
            Language language = Language.findLanguageByID("HTML");
            FileType fileType = language != null ? language.getAssociatedFileType() : null;
            //myTextViewer = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, fileType, true, false);
            Document myDocument = EditorFactory.getInstance().createDocument("");
            myTextViewer = (EditorImpl) EditorFactory.getInstance().createViewer(myDocument, project);
            if (fileType != null) myTextViewer.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType));
        } else {
            // Setup the editor pane for rendering HTML.
            myTextViewer = null;
            jEditorPane = new JEditorPane();
            scrollPane = new JBScrollPane(jEditorPane);

            setStyleSheet();

            // Add a custom link listener which can resolve local link references.
            jEditorPane.addHyperlinkListener(new MultiMarkdownLinkListener(jEditorPane, project, document));
            jEditorPane.setEditable(false);

            // Set the editor pane caret position to top left, and do not let it reset it
            jEditorPane.getCaret().setMagicCaretPosition(new Point(0, 0));
            ((DefaultCaret) jEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
    }

    protected FileType findHtmlFileType() {
        FileType[] fileTypes = FileTypeManager.getInstance().getRegisteredFileTypes();
        for (FileType fileType : fileTypes) {
            String name = fileType.getName();
            //if (name.equals("HTML")) return fileType;
            if (name.equals("Scratch")) {
                return fileType;
            }
        }
        return fileTypes[0];
    }

    protected void delayedHtmlPreviewUpdate(final boolean fullKit) {
        if (updateDelayTimer != null) {
            updateDelayTimer.cancel();
            updateDelayTimer = null;
        }

        if (!isEditorTabVisible)
            return;

        updateDelayTimer = new Timer();
        updateDelayTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                ApplicationManager.getApplication().invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        previewIsObsolete = true;

                        if (fullKit) {
                            setStyleSheet();
                            processor.remove();     // make it re-initialize when accessed
                        }

                        updateHtmlContent(true);
                    }
                }, ModalityState.any());
            }
        }, getUpdateDelay());
    }

    protected void setStyleSheet() {
        if (isRawHtml) return;

        MultiMarkdownEditorKit htmlKit = new MultiMarkdownEditorKit(document);

        final StyleSheet style = new StyleSheet();

        if (getCustomCss().equals("")) {
            style.importStyleSheet(MultiMarkdownPreviewEditor.class.getResource(
                    MultiMarkdownGlobalSettings.getInstance().isDarkHtmlPreview() ? PREVIEW_STYLESHEET_DARK : PREVIEW_STYLESHEET_LIGHT));
        } else {
            try {
                style.loadRules(new StringReader(getCustomCss()), null);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        htmlKit.setStyleSheet(style);

        jEditorPane.setEditorKit(htmlKit);
    }

    /**
     * Get the {@link java.awt.Component} to display as this editor's UI.
     *
     * @return a scrollable {@link JEditorPane}.
     */
    @NotNull
    public JComponent getComponent() {
        return scrollPane != null ? scrollPane : myTextViewer.getComponent();
    }

    /**
     * Get the component to be focused when the editor is opened.
     *
     * @return {@link #scrollPane}
     */
    @Nullable
    public JComponent getPreferredFocusedComponent() {
        return scrollPane != null ? scrollPane : myTextViewer.getComponent();
    }

    /**
     * Get the editor displayable name.
     *
     * @return editor name
     */
    @NotNull
    @NonNls
    public String getName() {
        return isRawHtml ? TEXT_EDITOR_NAME : PREVIEW_EDITOR_NAME;
    }

    /**
     * Get the state of the editor.
     * <p/>
     * Just returns {@link FileEditorState#INSTANCE} as {@link MultiMarkdownPreviewEditor} is stateless.
     *
     * @param level the level.
     *
     * @return {@link FileEditorState#INSTANCE}
     *
     * @see #setState(com.intellij.openapi.fileEditor.FileEditorState)
     */
    @NotNull
    public FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    /**
     * Set the state of the editor.
     * <p/>
     * Does not do anything as {@link MultiMarkdownPreviewEditor} is stateless.
     *
     * @param state the new state.
     *
     * @see #getState(com.intellij.openapi.fileEditor.FileEditorStateLevel)
     */
    public void setState(@NotNull FileEditorState state) {
    }

    /**
     * Indicates whether the document content is modified compared to its file.
     *
     * @return {@code false} as {@link MultiMarkdownPreviewEditor} is read-only.
     */
    public boolean isModified() {
        return false;
    }

    /**
     * Indicates whether the editor is valid.
     *
     * @return {@code true} if {@link #document} content is readable.
     */
    public boolean isValid() {
        return true;
    }

    /**
     * Invoked when the editor is selected.
     * <p/>
     * Update the HTML content if obsolete.
     */
    public void selectNotify() {
        isActive = true;
        if (previewIsObsolete) {
            updateHtmlContent(false);
        }
    }

    protected void updateRawHtmlText(final String htmlTxt) {
        final DocumentEx myDocument = myTextViewer.getDocument();

        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                CommandProcessor.getInstance().executeCommand(project, new Runnable() {
                    @Override
                    public void run() {
                        myDocument.replaceString(0, myDocument.getTextLength(), htmlTxt);
                        final CaretModel caretModel = myTextViewer.getCaretModel();
                        if (caretModel.getOffset() >= myDocument.getTextLength()) {
                            caretModel.moveToOffset(myDocument.getTextLength());
                        }
                    }
                }, null, null, UndoConfirmationPolicy.DEFAULT, myDocument);
            }
        });
    }

    private void updateHtmlContent(boolean force) {
        if (updateDelayTimer != null) {
            updateDelayTimer.cancel();
            updateDelayTimer = null;
        }

        if (previewIsObsolete && isEditorTabVisible && (isActive || force)) {
            try {
                final String html = processor.get().markdownToHtml(document.getText());
                if (isRawHtml) {
                    final String htmlTxt = isShowModified() ? postProcessHtml(html) : html;
                    //myTextViewer.setText(htmlTxt);
                    updateRawHtmlText(htmlTxt);
                } else {
                    jEditorPane.setText(postProcessHtml(html));
                }
                previewIsObsolete = false;

                // here we can find our HTML Text counterpart but it is better to keep it separate for now
                //VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                //FileEditorManager manager = FileEditorManager.getInstance(project);
                //FileEditor[] editors = manager.getEditors(file);
                //for (int i = 0; i < editors.length; i++)
                //{
                //    if (editors[i] == this)
                //    {
                //        if (editors.length > i && editors[i+1] instanceof MultiMarkdownPreviewEditor) {
                //            // update its html too
                //            MultiMarkdownPreviewEditor htmlEditor = (MultiMarkdownPreviewEditor)editors[i+1];
                //            boolean showModified = MultiMarkdownGlobalSettings.getInstance().isShowHtmlTextAsModified();
                //            htmlEditor.setHtmlContent("<div id=\"multimarkdown-preview\">\n" + (showModified ? procHtml : html) + "\n</div>\n");
                //            break;
                //        }
                //    }
                //}
            } catch (Exception e) {
                LOGGER.error("Failed processing Markdown document", e);
            }
        }
    }

    public void setHtmlContent(String html) {
        jEditorPane.setText(html);
    }

    protected String postProcessHtml(String html) {
        // scan for <table>, </table>, <tr>, </tr> and other tags we modify, this could be done with a custom plugin to pegdown but
        // then it would be more trouble to get un-modified HTML.
        String result = "<body class=\"multimarkdown-preview\">\n";
        String regex = "(<table>|<thead>|<tbody>|<tr>|<hr/>|<del>|</del>|</p>|<li>\\n*\\s*<p>";
        String regexTail = ")";
        Boolean taskLists = isTaskLists();
        Boolean iconBullets = isIconBullets();

        if (taskLists) {
            regex += "|<li class=\"task-list-item\">\\n*\\s*<p>|<li class=\"task-list-item\">|<li>\\[x\\]|<li>\\[ \\]|<li>\\n*\\s*<p>\\[x\\]|<li>\\n*\\s*<p>\\[ \\]";
        }
        if (iconBullets) {
            regex += "|<ul>|<ol>|</ul>|</ol>";
            regexTail = "|<li>" + regexTail;
        }
        regex += regexTail;

        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        int lastPos = 0;
        int rowCount = 0;
        boolean[] isOrderedList = new boolean[20];
        int listDepth = -1;

        while (m.find()) {
            String found = m.group();
            if (lastPos < m.start(0)) {
                result += html.substring(lastPos, m.start(0));
            }

            if (found.equals("</p>")) {
                result += found;
            } else if (found.equals("<table>")) {
                rowCount = 0;
                result += found;
            } else if (found.equals("<thead>")) {
                result += found;
            } else if (found.equals("<tbody>")) {
                result += found;
            } else if (iconBullets && found.equals("<ol>")) {
                if (listDepth + 1 >= isOrderedList.length) listDepth = isOrderedList.length - 2;
                isOrderedList[++listDepth] = true;
                result += found;
            } else if (iconBullets && found.equals("</ol>")) {
                if (listDepth >= 0) listDepth--;
                result += found;
            } else if (iconBullets && found.equals("<ul>")) {
                if (listDepth + 1 >= isOrderedList.length) listDepth = isOrderedList.length - 2;
                isOrderedList[++listDepth] = false;
                result += found;
            } else if (iconBullets && found.equals("</ul>")) {
                if (listDepth >= 0) listDepth--;
                result += found;
            } else if (found.equals("<tr>")) {
                rowCount++;
                result += "<tr class=\"" + (rowCount == 1 ? "first-child" : (rowCount & 1) != 0 ? "odd-child" : "even-child") + "\">";
            } else if (found.equals("<hr/>")) {
                result += "<div class=\"hr\">&nbsp;</div>";
            } else if (found.equals("<del>")) {
                result += "<span class=\"del\">";
            } else if (found.equals("</del>")) {
                result += "</span>";
            } else if (iconBullets && listDepth >= 0 && !isOrderedList[listDepth] && found.equals("<li>")) {
                result += "<li class=\"bullet\"><input type=\"checkbox\" class=\"list-item-bullet\"></input>";
            } else {
                if (taskLists && found.equals("<li>[x]")) {
                    result += "<li class=\"task\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" checked=\"checked\" disabled=\"disabled\">";
                } else if (taskLists && found.equals("<li>[ ]")) {
                    result += "<li class=\"task\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled=\"disabled\">";
                } else if (taskLists && found.equals("<li class=\"task-list-item\">")) {
                    result += "<li class=\"task\">";
                } else {
                    // here we have <li>\n*\s*<p>, need to strip out \n*\s* so we can match them easier
                    String foundWithP = found;
                    foundWithP = foundWithP.replaceAll("<li>\\n*\\s*<p>", "<li><p>");
                    found = foundWithP.replaceAll("<li class=\"task-list-item\">\\n*\\s*<p>", "<li class=\"task-list-item\"><p>");
                    if (found.equals("<li><p>")) {
                        if (iconBullets && listDepth >= 0 && !isOrderedList[listDepth]) {
                            result += "<li class=\"bulletp\"><p class=\"p\"><input type=\"checkbox\" class=\"list-item-bullet\"></input>";
                        } else {
                            result += "<li class=\"p\"><p class=\"p\">";
                        }
                    } else if (taskLists && found.equals("<li><p>[x]")) {
                        result += "<li class=\"taskp\"><p class=\"p\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" checked=\"checked\" disabled=\"disabled\">";
                    } else if (taskLists && found.equals("<li><p>[ ]")) {
                        result += "<li class=\"taskp\"><p class=\"p\"><input type=\"checkbox\" class=\"task-list-item-checkbox\" disabled=\"disabled\">";
                    } else if (taskLists && found.equals("<li class=\"task-list-item\"><p>")) {
                        result += "<li class=\"taskp\"><p class=\"p\">";
                    } else {
                        result += found;
                    }
                }
            }

            lastPos = m.end(0);
        }

        if (lastPos < html.length()) {
            result += html.substring(lastPos);
        }

        result += "\n</body>\n";
        return result;
    }

    /**
     * Invoked when the editor is deselected.
     * <p/>
     * Does nothing.
     */
    public void deselectNotify() {
        isActive = false;
    }

    /**
     * Add specified listener.
     * <p/>
     * Does nothing.
     *
     * @param listener the listener.
     */
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    /**
     * Remove specified listener.
     * <p/>
     * Does nothing.
     *
     * @param listener the listener.
     */
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    /**
     * Get the background editor highlighter.
     *
     * @return {@code null} as {@link MultiMarkdownPreviewEditor} does not require highlighting.
     */
    @Nullable
    public BackgroundEditorHighlighter getBackgroundHighlighter() {
        return null;
    }

    /**
     * Get the current location.
     *
     * @return {@code null} as {@link MultiMarkdownPreviewEditor} is not navigable.
     */
    @Nullable
    public FileEditorLocation getCurrentLocation() {
        return null;
    }

    /**
     * Get the structure view builder.
     *
     * @return TODO {@code null} as parsing/PSI is not implemented.
     */
    @Nullable
    public StructureViewBuilder getStructureViewBuilder() {
        return null;
    }

    /** Dispose the editor. */
    public void dispose() {
        if (!isReleased) {
            isReleased = true;
            if (jEditorPane != null) {
                jEditorPane.removeAll();
            }

            if (globalSettingsListener != null) {
                MultiMarkdownGlobalSettings.getInstance().removeListener(globalSettingsListener);
                globalSettingsListener = null;
            }

            if (myTextViewer != null) {
                final Application application = ApplicationManager.getApplication();
                final Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!myTextViewer.isDisposed()) {
                            EditorFactory.getInstance().releaseEditor(myTextViewer);
                        }
                    }
                };

                if (application.isUnitTestMode() || application.isDispatchThread()) {
                    runnable.run();
                } else {
                    application.invokeLater(runnable);
                }
            }

            Disposer.dispose(this);
        }
    }
}
