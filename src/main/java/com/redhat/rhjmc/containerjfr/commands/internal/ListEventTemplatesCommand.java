package com.redhat.rhjmc.containerjfr.commands.internal;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.redhat.rhjmc.containerjfr.commands.SerializableCommand;
import com.redhat.rhjmc.containerjfr.core.tui.ClientWriter;

@Singleton
class ListEventTemplatesCommand extends AbstractConnectedCommand implements SerializableCommand {

    private final ClientWriter cw;

    @Inject
    ListEventTemplatesCommand(ClientWriter cw) {
        this.cw = cw;
    }

    @Override
    public String getName() {
        return "list-event-templates";
    }

    /** No args expected. Prints a list of available event templates in the target JVM. */
    @Override
    public void execute(String[] args) throws Exception {
        cw.println("Available recording templates:");
        getConnection()
                .getTemplateService()
                .getTemplates()
                .forEach(
                        template ->
                                cw.println(
                                        String.format(
                                                "\t[%s]\t%s:\t%s",
                                                template.getProvider(),
                                                template.getName(),
                                                template.getDescription())));
    }

    @Override
    public Output<?> serializableExecute(String[] args) {
        try {
            return new ListOutput<>(getConnection().getTemplateService().getTemplates());
        } catch (Exception e) {
            return new ExceptionOutput(e);
        }
    }

    @Override
    public boolean validate(String[] args) {
        if (args.length != 0) {
            cw.println("No arguments expected");
            return false;
        }
        return true;
    }
}
