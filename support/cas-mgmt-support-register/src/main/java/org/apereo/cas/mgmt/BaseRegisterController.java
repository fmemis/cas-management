package org.apereo.cas.mgmt;

import org.apereo.cas.configuration.CasManagementConfigurationProperties;
import org.apereo.cas.configuration.model.RegisterNotifications;
import org.apereo.cas.configuration.model.support.email.EmailProperties;
import org.apereo.cas.mgmt.authentication.CasUserProfile;
import org.apereo.cas.mgmt.factory.VersionControlManagerFactory;
import org.apereo.cas.mgmt.util.CasManagementUtils;
import org.apereo.cas.notifications.CommunicationsManager;
import org.apereo.cas.services.RegexRegisteredService;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceContact;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.util.RegisteredServiceJsonSerializer;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Base Controller for handling requests from Staff and Faculty.
 *
 * @author Travis Schmidt
 * @since 6.0
 */
@Slf4j
public abstract class BaseRegisterController {

    private static final int MAX_EMAIL_LENGTH = 100;

    /**
     * Manager Factory.
     */
    protected final VersionControlManagerFactory managerFactory;

    /**
     * Management Configuration properties.
     */
    protected final CasManagementConfigurationProperties managementProperties;

    /**
     * EMail Manager.
     */
    protected final CommunicationsManager communicationsManager;

    /**
     * Services Manager.
     */
    protected final ServicesManager published;

    /**
     * Notifications Text.
     */
    protected final RegisterNotifications notifications;

    @SneakyThrows
    protected BaseRegisterController(final VersionControlManagerFactory managerFactory,
                                  final CasManagementConfigurationProperties managementProperties,
                                  final CommunicationsManager communicationsManager,
                                  final ServicesManager published){
        this.managerFactory = managerFactory;
        this.managementProperties = managementProperties;
        this.communicationsManager = communicationsManager;
        this.published = published;
        this.notifications = managementProperties.getRegister().getNotifications();
    }

    /**
     * Mapped method that accepts a submitted service by end user and adds is to Submissions queue.
     *
     * @param authentication - the user
     * @param service - the Service to be submitted
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @SneakyThrows
    public void submit(final Authentication authentication,
                       final @RequestBody RegisteredService service) {
        val id = service.getId() > 0 ? service.getId() : LocalDateTime.now(ZoneId.systemDefault());
        val path = Paths.get(managementProperties.getSubmissions().getSubmitDir() + "/submit-" + id +".json");
        val out = Files.newOutputStream(path);
        CasManagementUtils.jsonTo(out, service);
        out.close();
        val casUserProfile = CasUserProfile.from(authentication);
        setSubmitter(path, casUserProfile);
        sendMessage(casUserProfile, notifications.getSubmit(), service.getName(), service.getName());
    }

    /**
     * Mapped method to handle updating a service submitted by a user.
     *
     * @param authentication - the user
     * @param pair - the Service to update
     */
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    @SneakyThrows
    public void registerSave(final Authentication authentication,
                             final @RequestBody DataPair pair) {
        val service = pair.getRight();
        val id = pair.getLeft();
        val casUserProfile = CasUserProfile.from(authentication);
        saveService(service, id, casUserProfile);
    }

    /**
     * Request to delete a service.
     *
     * @param authentication - the user
     * @param id - the id
     */
    @DeleteMapping("{id}")
    @ResponseStatus(HttpStatus.OK)
    @SneakyThrows
    public void remove(final Authentication authentication,
                       final @PathVariable String id) {
        val casUserProfile = CasUserProfile.from(authentication);
        val manager = managerFactory.master();
        val service = manager.findServiceBy(Long.parseLong(id));
        val path = Paths.get(managementProperties.getSubmissions().getSubmitDir() + "/remove-" + service.getId() + ".json");
        val out = Files.newOutputStream(path);
        CasManagementUtils.jsonTo(out, service);
        out.close();
        setSubmitter(path, casUserProfile);
        sendMessage(casUserProfile, notifications.getRemove(), service.getName(), service.getName());
    }

    /**
     * Mapped method that returns the RegisteredService by the passed Id form the master repo.
     *
     * @param authentication - the user
     * @param id - assigned id of the service
     * @return - the requested RegisteredService
     */
    @GetMapping("{id}")
    @SneakyThrows
    public RegisteredService getRegisterService(final Authentication authentication,
                                                final @PathVariable String id) {
        val casUserProfile = CasUserProfile.from(authentication);
        val email = casUserProfile.getEmail();
        val manager = managerFactory.master();
        val svc = manager.findServiceBy(Long.parseLong(id));
        checkOwner(svc.getContacts(), email);
        return svc;
    }

    /**
     * Method will cancel a pending submission.
     *
     * @param authentication - the user
     * @param id - id of pending submission
     * @throws IllegalAccessException - Insufficient permissions
     * @throws IOException - failed to delete file
     */
    @DeleteMapping("cancel")
    @ResponseStatus(HttpStatus.OK)
    public void cancel(final Authentication authentication,
                       final @RequestParam String id) throws IllegalAccessException, IOException {
        val casUserProfile = CasUserProfile.from(authentication);
        val service = Paths.get(managementProperties.getSubmissions().getSubmitDir() + "/" + id);
        if (!isSubmitter(service, casUserProfile)) {
            throw new IllegalAccessException("You are not the original submitter of the request");
        }
        Files.delete(service);
    }

    /**
     * Submits a request to promote a service.
     *
     * @param id - the id
     * @param authentication - the user
     */
    @GetMapping("promote/{id}")
    @SneakyThrows
    public void promote(final @PathVariable Long id,
                        final Authentication authentication) {
        val casUserProfile = CasUserProfile.from(authentication);
        val manager = managerFactory.master();
        val service = manager.findServiceBy(id);
        ((RegexRegisteredService) service).setEnvironments(null);
        saveService(service, String.valueOf(id), casUserProfile);
    }

    private boolean isSubmitter(final Path p, final CasUserProfile casUserProfile) {
        return getSubmitter(p)[0].equals(casUserProfile.getEmail());
    }

    private String[] getSubmitter(final Path path) {
        try {
            val email = new byte[MAX_EMAIL_LENGTH];
            Files.getFileAttributeView(path, UserDefinedFileAttributeView.class)
                    .read("original_author", ByteBuffer.wrap(email));
            return new String(email, StandardCharsets.UTF_8).trim().split(":");
        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
            return new String[] {StringUtils.EMPTY, StringUtils.EMPTY};
        }
    }

    private void sendMessage(final CasUserProfile user,
                             final EmailProperties emailProperties,
                             final String textArg,
                             final String subjectArg) {
        if (communicationsManager.isMailSenderDefined()) {
            emailProperties.setSubject(MessageFormat.format(emailProperties.getSubject(), subjectArg));
            communicationsManager.email(emailProperties, user.getEmail(), MessageFormat.format(emailProperties.getText(), textArg));
        }
    }

    /**
     * Saves a submitted service.
     *
     * @param service - the service
     * @param id - the id of the service
     * @param casUserProfile - user profile
     * @throws IOException - failed to save file
     */
    protected void saveService(final RegisteredService service, final String id, final CasUserProfile casUserProfile) throws IOException {
        val serializer = new RegisteredServiceJsonSerializer();
        val path = isNumber(id) ? Paths.get(managementProperties.getSubmissions().getSubmitDir() + "/edit-" + service.getId() + ".json")
                : Paths.get(managementProperties.getSubmissions().getSubmitDir() + "/" + id);
        val out = Files.newOutputStream(path);
        serializer.to(out, service);
        out.close();
        setSubmitter(path, casUserProfile);
        sendMessage(casUserProfile, notifications.getChange(), service.getName(), service.getName());
    }

    /**
     * Returns the contact if the passed email is an owner of the service or null.
     *
     * @param contacts - List of contacts for a service.
     * @param email - Email to search for
     * @return - RegisteredServiceContact or null
     */
    protected RegisteredServiceContact owner(final List<RegisteredServiceContact> contacts, final String email) {
        return contacts.stream().filter(c -> email.equalsIgnoreCase(c.getEmail())).findAny().orElse(null);
    }

    private void checkOwner(final List<RegisteredServiceContact> contacts, final String email) throws IllegalAccessException {
        if (owner(contacts, email) == null) {
            throw new IllegalAccessException("You do not own this service.");
        }
    }

    private void setSubmitter(final Path path, final CasUserProfile casUserProfile) throws IOException{
        val payload = casUserProfile.getEmail() + ":" + casUserProfile.getFirstName() + " "
                + casUserProfile.getFamilyName();
        Files.getFileAttributeView(path, UserDefinedFileAttributeView.class)
                .write("original_author", ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean isNumber(final String id) {
        try {
            Long.parseLong(id);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    @Data
    private static class DataPair {
        private String left;
        private RegisteredService right;
    }
}
