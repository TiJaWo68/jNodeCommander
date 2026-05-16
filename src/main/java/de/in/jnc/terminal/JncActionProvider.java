package de.in.jnc.terminal;

import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.TerminalActionProvider;

/**
 * A custom {@link TerminalActionProvider} that adds a <b>Credentials...</b>
 * entry to the JediTerm context menu (appears above "Find").
 * <p>
 * Delegates to {@link CredentialsService} for fetching and displaying
 * credentials. The {@code textInserter} callback inserts the selected
 * value at the terminal cursor position.
 * <p>
 * Set this as the {@code nextProvider} on {@code JediTermWidget} (after
 * {@code TerminalPanel}) so that its actions appear <b>before</b> the
 * built-in "Find" item (due to the reversal in {@link TerminalAction#buildMenu}).
 * The "Settings..." entry is added at the very end of the menu by overriding
 * {@link TerminalPanel#createPopupMenu} in {@code ConnectionFrame}.
 */
public class JncActionProvider implements TerminalActionProvider {

    private final CredentialsService credentialsService;
    private final Consumer<String> textInserter;

    /**
     * Creates a new JncActionProvider.
     *
     * @param credentialsService the shared credentials service (already initialized)
     * @param textInserter       callback to insert text at the terminal cursor position
     */
    public JncActionProvider(CredentialsService credentialsService, Consumer<String> textInserter) {
        this.credentialsService = credentialsService;
        this.textInserter = textInserter;
    }

    @Override
    public List<TerminalAction> getActions() {
        return List.of(
                new TerminalAction(new TerminalActionPresentation("Credentials...", Collections.emptyList()), e -> {
                    credentialsService.showCredentialsDialog(null, textInserter);
                    return true;
                }).withMnemonicKey(KeyEvent.VK_C)
        );
    }

    @Override
    public TerminalActionProvider getNextProvider() {
        return null;
    }

    @Override
    public void setNextProvider(TerminalActionProvider provider) {
        // Not needed – this is the terminal provider in the chain
    }
}
