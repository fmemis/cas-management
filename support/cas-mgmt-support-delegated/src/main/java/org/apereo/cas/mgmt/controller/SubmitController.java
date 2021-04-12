package org.apereo.cas.mgmt.controller;

import org.apereo.cas.configuration.CasManagementConfigurationProperties;
import org.apereo.cas.mgmt.GitUtil;
import org.apereo.cas.mgmt.authentication.CasUserProfile;
import org.apereo.cas.mgmt.domain.BranchData;
import org.apereo.cas.mgmt.factory.RepositoryFactory;
import org.apereo.cas.notifications.CommunicationsManager;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Controller to handle submit requests.
 *
 * @author Travis Schmidt
 * @since 6.0
 */
@RestController("submitController")
@RequestMapping(path = "api/submit", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SubmitController {

    private final RepositoryFactory repositoryFactory;
    private final CasManagementConfigurationProperties managementProperties;
    private final CommunicationsManager communicationsManager;

    /**
     * Method commits the working dir of the user and creates a submit branch that is made into a pull request.
     *
     * @param authentication - the user
     * @param msg      - message from user
     */
    @PostMapping
    @SneakyThrows
    public void submitPull(final Authentication authentication,
                           final @RequestBody String msg) {
        val user = CasUserProfile.from(authentication);
        try (GitUtil git = repositoryFactory.from(authentication)) {
            if (git.isUndefined()) {
                throw new IllegalArgumentException("No changes to submit");
            }
            val timestamp = LocalDateTime.now(ZoneId.systemDefault());
            val branchName = "submit-" + timestamp;
            val submitName = user.getId() + '_' + timestamp;

            git.addWorkingChanges();
            val commit = git.commit(user, msg);
            git.createBranch(branchName, "origin/master");
            git.cherryPickCommit(commit);
            git.commit(user, msg);
            git.createPullRequest(commit, submitName);
            git.checkout("master");
            sendSubmitMessage(submitName, user);
        }
    }

    private void sendSubmitMessage(final String submitName, final CasUserProfile user) {
        if (communicationsManager.isMailSenderDefined()) {
            val emailProps = managementProperties.getDelegated().getNotifications().getSubmit();
            emailProps.setSubject(MessageFormat.format(emailProps.getSubject(), submitName));
            communicationsManager.email(emailProps, user.getEmail(), emailProps.getText());
        }
    }

    /**
     * Method will create and return a list of branches that have been submitted as pull request by users.
     *
     * @param authentication  - HttpServletRequest
     * @return - List of BranchData
     */
    @GetMapping
    @SneakyThrows
    public List<BranchData> submits(final Authentication authentication) {
        val user = CasUserProfile.from(authentication);
        try (GitUtil git = repositoryFactory.masterRepository()) {
            return git.branches()
                    .filter(r -> r.getName().contains('/' + user.getId() + '_'))
                    .map(git::mapBranches)
                    .map(DelegatedUtil::createBranch)
                    .collect(toList());
        }
    }

    /**
     * Method will revert a submitted pull request from a user's repository if it has been rejected by an admin.
     *
     * @param authentication    - the user
     * @param branch - Name of the pull requet
     */
    @GetMapping("revert/{branch}")
    @ResponseStatus(HttpStatus.OK)
    @SneakyThrows
    public void revertSubmit(final Authentication authentication,
                             final @PathVariable String branch) {
        val user = CasUserProfile.from(authentication);
        try (GitUtil git = repositoryFactory.from(authentication)) {
            if (git.isUndefined()) {
                throw new IllegalArgumentException("No changes to revert");
            }
            git.reset(git.findCommitBeforeSubmit(branch));
        }
        try (GitUtil master = repositoryFactory.masterRepository()) {
            master.markAsReverted(branch, user);
        }
    }
}
