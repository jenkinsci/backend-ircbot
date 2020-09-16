package org.jenkinsci.backend.ircbot.hosting;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jenkinsci.backend.ircbot.HostingChecker;
import org.jenkinsci.backend.ircbot.JiraHelper;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.jenkinsci.backend.ircbot.HostingChecker.LOWEST_JENKINS_VERSION;

public class MavenVerifier implements BuildSystemVerifier {
    private final Log log = LogFactory.getLog(MavenVerifier.class);

    public static final Version LOWEST_PARENT_POM_VERSION = new Version(4, 0, 0);
    public static final Version PARENT_POM_WITH_JENKINS_VERSION = new Version(2);

    public static final String INVALID_POM = "The pom.xml file in the root of the origin repository is not valid";
    public static final String SPECIFY_LICENSE = "Please specify a license in your pom.xml file using the <licenses> tag. See https://maven.apache.org/pom.html#Licenses for more information.";
    public static final String MISSING_POM_XML = "No pom.xml found in root of project, if you are using a different build system, or this is not a plugin, you can disregard this message";

    public static final String SHOULD_BE_IO_JENKINS_PLUGINS = "The <groupId> from the pom.xml should be `io.jenkins.plugins` instead of `org.jenkins-ci.plugins`";

    @Override
    public void verify(IssueRestClient issueClient, Issue issue, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GitHub github = GitHub.connect();
        String forkTo = JiraHelper.getFieldValueOrDefault(issue, JiraHelper.FORK_TO_JIRA_FIELD, "");
        String forkFrom = JiraHelper.getFieldValueOrDefault(issue, JiraHelper.FORK_FROM_JIRA_FIELD, "");

        if(StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https:\\/\\/github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if(m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner+"/"+repoName);
                try {
                    GHContent pomXml = repo.getFileContent("pom.xml");
                    if(pomXml != null && pomXml.isFile()) {
                        InputStream contents = pomXml.read();
                        MavenXpp3Reader reader = new MavenXpp3Reader();
                        Model model = reader.read(contents);

                        try {
                            // check for multimodule project...
                            if(!model.getModules().isEmpty()) {
                                // determine what to do in this case...
                            }

                            checkArtifactId(model, forkTo, hostingIssues);
                            checkParentInfoAndJenkinsVersion(model, hostingIssues);
                            checkName(model, hostingIssues);
                            checkLicenses(issue, model, hostingIssues);
                            checkGroupId(model, hostingIssues);
                            checkRepositories(model, hostingIssues);
                            checkPluginRepositories(model, hostingIssues);
                        } catch(Exception e) {
                            log.error(String.format("Exception occurred trying to look at pom.xml: %s", e.toString()));
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
                        }
                    }
                } catch(GHFileNotFoundException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.WARNING, MISSING_POM_XML));
                } catch(XmlPullParserException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }
        }
    }

    @Override
    public boolean hasBuildFile(Issue issue) throws IOException {
        return HostingChecker.fileExistsInRepo(issue, "pom.xml");
    }

    private void checkArtifactId(Model model, String forkTo, HashSet<VerificationMessage> hostingIssues) {
        try {
            if(StringUtils.isBlank(forkTo)) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing value in Jira for 'New Repository Name' field"));
            }

            String artifactId = model.getArtifactId();
            if(StringUtils.isNotBlank(artifactId)) {
                if(StringUtils.isNotBlank(forkTo) && !artifactId.equals(forkTo.replace("-plugin", ""))) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <artifactId> from the pom.xml (%s) is incorrect, it should be '%s' ('New Repository Name' field with \"-plugin\" removed)", artifactId, forkTo.replace("-plugin", "")));
                }

                if(artifactId.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <artifactId> from the pom.xml (%s) should not contain \"Jenkins\"", artifactId));
                }

                if(!artifactId.toLowerCase().equals(artifactId)) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <artifactId> from the pom.xml (%s) should be all lower case", artifactId));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The pom.xml file does not contain a valid <artifactId> for the project"));
            }
        } catch(Exception e) {
            log.error(String.format("Error trying to access artifactId: %s", e.toString()));
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkGroupId(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            String groupId = model.getGroupId();
            if(StringUtils.isNotBlank(groupId)) {
                if(groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, SHOULD_BE_IO_JENKINS_PLUGINS));
                }
            } else {
                Parent parent = model.getParent();
                if(parent != null) {
                    groupId = parent.getGroupId();
                    if(StringUtils.isNotBlank(groupId)) {
                        if(groupId.equals("org.jenkins-ci.plugins")) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You must add a <groupdId> in your pom.xml with the value `io.jenkins.plugins`."));
                        }
                    }
                }
            }
        } catch(Exception e) {
            log.error(String.format("Error trying to access groupId: %s", e.toString()));
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkName(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            String name = model.getName();
            if(StringUtils.isNotBlank(name)) {
                if(name.toLowerCase().contains("jenkins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <name> field in the pom.xml should not contain \"Jenkins\""));
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The pom.xml file does not contain a valid <name> for the project"));
            }
        } catch(Exception e) {
            log.error(String.format("Error trying to access <name>: %s", e.getMessage()));
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, INVALID_POM));
        }
    }

    private void checkParentInfoAndJenkinsVersion(Model model, HashSet<VerificationMessage> hostingIssues) {
        try {
            Parent parent = model.getParent();
            if(parent != null) {
                String groupId = parent.getGroupId();
                if(StringUtils.isNotBlank(groupId) && !groupId.equals("org.jenkins-ci.plugins")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The groupId for your parent pom is not \"org.jenkins-ci.plugins,\" if this is not a plugin hosting request, you can disregard this notice."));
                }

                String version = parent.getVersion();
                if(StringUtils.isNotBlank(version)) {
                    Version parentVersion = new Version(version);
                    if(parentVersion.compareTo(LOWEST_PARENT_POM_VERSION) < 0) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The parent pom version '%s' should be at least %s or higher", parentVersion, LOWEST_PARENT_POM_VERSION));
                    }

                    if(parentVersion.compareTo(PARENT_POM_WITH_JENKINS_VERSION) >= 0) {
                        Version jenkinsVersion = null;
                        if(model.getProperties().containsKey("jenkins.version")) {
                            jenkinsVersion = new Version(model.getProperties().get("jenkins.version").toString());
                        }

                        if(jenkinsVersion != null && jenkinsVersion.compareTo(HostingChecker.LOWEST_JENKINS_VERSION) < 0) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Your pom.xml's <jenkins.version>(%s)</jenkins.version> does not meet the minimum Jenkins version required, please update your <jenkins.version> to at least %s", jenkinsVersion, LOWEST_JENKINS_VERSION));
                        }
                    }
                }
            }
        } catch(Exception e) {
            log.error("Error trying to access the <parent> information: "+e.getMessage());
        }
    }

    private void checkLicenses(Issue issue, Model model, HashSet<VerificationMessage> hostingIssues) {
        // first check the pom.xml
        List<License> licenses = model.getLicenses();
        if(licenses.size() == 0) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, SPECIFY_LICENSE));
        }
    }

    private void checkRepositories(Model model, HashSet<VerificationMessage> hostingIssues) {
        for(Repository r : model.getRepositories()) {
            if(r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if(!uri.getScheme().equals("https")) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You MUST use an https:// scheme in your pom.xml for the <repository><url></url></repository> tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <repository><url></url></repository> in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }

    private void checkPluginRepositories(Model model, HashSet<VerificationMessage> hostingIssues) {
        for(Repository r : model.getPluginRepositories()) {
            if(r.getUrl().contains("repo.jenkins-ci.org") || r.getId().contains("repo.jenkins-ci.org")) {
                try {
                    URI uri = new URI(r.getUrl());
                    if(!uri.getScheme().equals("https")) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "You MUST use an https:// scheme in your pom.xml for the <pluginRepository><url></url></pluginRepository> tag for repo.jenkins-ci.org"));
                    }
                } catch (URISyntaxException e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The <pluginRepository><url></url></pluginRepository> in your pom.xml for 'repo.jenkins-ci.org' has an invalid URL"));
                }
            }
        }
    }
}
