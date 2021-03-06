package hudson.plugins.jira;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import hudson.Extension;
import hudson.MarkupText;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import hudson.scm.ChangeLogAnnotator;
import hudson.scm.ChangeLogSet.Entry;

/**
 * {@link ChangeLogAnnotator} that picks up JIRA issue IDs.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class JiraChangeLogAnnotator extends ChangeLogAnnotator {

    private static final Logger LOGGER = Logger.getLogger(JiraChangeLogAnnotator.class.getName());

    public JiraChangeLogAnnotator() {
        super();
        LOGGER.fine("JiraChangeLogAnnotator created");
    }

    @Override
    public void annotate(Run<?, ?> build, Entry change, MarkupText text) {
        JiraSite site = getSiteForProject(build.getParent());
        
        if (site == null) {
            LOGGER.fine("not configured with JIRA");
            return;    // not configured with JIRA
        }

        LOGGER.log(Level.FINE, "Using site: {0}", site.url);

        // if there's any recorded detail information, try to use that, too.
        JiraBuildAction a = build.getAction(JiraBuildAction.class);

        Set<JiraIssue> issuesToBeSaved = new HashSet<JiraIssue>();

        Pattern pattern = site.getIssuePattern();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Using issue pattern: " + pattern);
        }

        String plainText = text.getText();

        Matcher m = pattern.matcher(plainText);

        while (m.find()) {
            if (m.groupCount() >= 1) {

                String id = m.group(1);

                if (StringUtils.isNotBlank(site.userName) && !site.existsIssue(id)) {
                    LOGGER.log(Level.INFO, "No known JIRA project corresponding to id: ''{0}''", id);
                    continue;
                }

                LOGGER.log(Level.INFO, "Annotating JIRA id: ''{0}''", id);

                URL url, alternativeUrl;
                try {
                    url = site.getUrl(id);
                } catch (MalformedURLException e) {
                    throw new AssertionError(e); // impossible
                }

                try {
                    alternativeUrl = site.getAlternativeUrl(id);
                    if (alternativeUrl != null) {
                        url = alternativeUrl;
                    }
                } catch (MalformedURLException e) {
                    LOGGER.log(Level.WARNING, "Failed to construct alternative URL for Jira link. " + e.getMessage());
                    // This should not fail, since we already have an URL object. Exceptions would happen elsewhere.
                    throw new AssertionError(e);
                }

                JiraIssue issue = null;
                if (a != null) {
                    issue = a.getIssue(id);
                }

                if (issue == null) {
                    try {
                        issue = site.getIssue(id);
                        if (issue != null) {
                            issuesToBeSaved.add(issue);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error getting remote issue " + id, e);
                    }
                }

                if (issue == null) {
                    text.addMarkup(m.start(1), m.end(1), "<a href='" + url + "'>", "</a>");
                } else {
                    text.addMarkup(m.start(1), m.end(1),
                            String.format("<a href='%s' tooltip='%s'>", url, Util.escape(issue.title)), "</a>");
                }

            } else {
                LOGGER.log(Level.WARNING, "The JIRA pattern " + pattern + " doesn't define a capturing group!");
            }
        }

        if (!issuesToBeSaved.isEmpty()) {
            saveIssues(build, a, issuesToBeSaved);
        }
    }

    private void saveIssues(Run<?, ?> build, JiraBuildAction a,
                            Set<JiraIssue> issuesToBeSaved) {
        if (a != null) {
            a.addIssues(issuesToBeSaved);
        } else {
            JiraBuildAction action = new JiraBuildAction(build, issuesToBeSaved);
            build.addAction(action);
        }

        try {
            build.save();
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Error saving updated build", e);
        }
    }

    JiraSite getSiteForProject(Job<?, ?> project) {
        return JiraSite.get(project);
    }
}
