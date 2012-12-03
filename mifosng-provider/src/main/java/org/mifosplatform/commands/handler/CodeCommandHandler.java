package org.mifosplatform.commands.handler;

import java.util.Arrays;
import java.util.List;

import org.joda.time.LocalDate;
import org.mifosplatform.commands.domain.CommandSource;
import org.mifosplatform.commands.service.ChangeDetectionService;
import org.mifosplatform.infrastructure.codes.command.CodeCommand;
import org.mifosplatform.infrastructure.codes.serialization.CodeCommandFromCommandJsonDeserializer;
import org.mifosplatform.infrastructure.codes.service.CodeWritePlatformService;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.mifosplatform.portfolio.client.service.RollbackTransactionAsCommandIsNotApprovedByCheckerException;
import org.mifosplatform.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CodeCommandHandler implements CommandSourceHandler {

    private final PlatformSecurityContext context;
    private final ChangeDetectionService changeDetectionService;
    private final CodeCommandFromCommandJsonDeserializer fromCommandJsonDeserializer;
    private final CodeWritePlatformService writePlatformService;

    @Autowired
    public CodeCommandHandler(final PlatformSecurityContext context, final ChangeDetectionService changeDetectionService,
            final CodeCommandFromCommandJsonDeserializer fromCommandJsonDeserializer, final CodeWritePlatformService writePlatformService) {
        this.context = context;
        this.changeDetectionService = changeDetectionService;
        this.fromCommandJsonDeserializer = fromCommandJsonDeserializer;
        this.writePlatformService = writePlatformService;
    }

    @Override
    public CommandSource handleCommandWithSupportForRollback(final CommandSource commandSource) {

        final AppUser maker = context.authenticatedUser();
        final LocalDate asToday = new LocalDate();

        final Long resourceId = commandSource.resourceId();
        final CodeCommand command = this.fromCommandJsonDeserializer.commandFromCommandJson(resourceId, commandSource.json());

        CommandSource commandSourceResult = commandSource.copy();

        if (commandSource.isCreate()) {
            try {
                Long newResourceId = this.writePlatformService.createCode(command);
                commandSourceResult.markAsChecked(maker, asToday);
                commandSourceResult.updateResourceId(newResourceId);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        } else if (commandSource.isUpdate()) {
            try {
                final String jsonOfChangesOnly = this.changeDetectionService.detectChangesOnUpdate(commandSource.resourceName(),
                        commandSource.resourceId(), commandSource.json());
                commandSourceResult.updateJsonTo(jsonOfChangesOnly);

                final CodeCommand changesOnly = this.fromCommandJsonDeserializer.commandFromCommandJson(resourceId, jsonOfChangesOnly);
                this.writePlatformService.updateCode(changesOnly);

                commandSourceResult.markAsChecked(maker, asToday);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        } else if (commandSource.isDelete()) {
            try {
                this.writePlatformService.deleteCode(command);
                commandSourceResult.markAsChecked(maker, asToday);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        }

        return commandSourceResult;
    }

    @Override
    public CommandSource handleCommandForCheckerApproval(final CommandSource commandSourceResult) {

        final AppUser checker = context.authenticatedUser();

        Long resourceId = commandSourceResult.resourceId();
        final CodeCommand command = this.fromCommandJsonDeserializer.commandFromCommandJson(resourceId, commandSourceResult.json(), true);

        if (commandSourceResult.isCreate()) {
            final List<String> allowedPermissions = Arrays.asList("ALL_FUNCTIONS", "CREATE_CODE_MAKER");
            context.authenticatedUser().validateHasPermissionTo("CREATE_CODE_MAKER", allowedPermissions);

            resourceId = this.writePlatformService.createCode(command);
            commandSourceResult.updateResourceId(resourceId);
            commandSourceResult.markAsChecked(checker, new LocalDate());
        } else if (commandSourceResult.isUpdate()) {
            final List<String> allowedPermissions = Arrays.asList("ALL_FUNCTIONS", "UPDATE_CODE_MAKER");
            context.authenticatedUser().validateHasPermissionTo("UPDATE_CODE_MAKER", allowedPermissions);

            this.writePlatformService.updateCode(command);
            commandSourceResult.markAsChecked(checker, new LocalDate());
        } else if (commandSourceResult.isDelete()) {
            final List<String> allowedPermissions = Arrays.asList("ALL_FUNCTIONS", "DELETE_CODE_MAKER");
            context.authenticatedUser().validateHasPermissionTo("DELETE_CODE_MAKER", allowedPermissions);

            this.writePlatformService.deleteCode(command);
            commandSourceResult.markAsChecked(checker, new LocalDate());
        }

        return commandSourceResult;
    }
}