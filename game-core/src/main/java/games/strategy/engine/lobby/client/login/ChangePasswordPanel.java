package games.strategy.engine.lobby.client.login;

import games.strategy.triplea.settings.ClientSetting;
import games.strategy.ui.Util;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import org.triplea.http.client.lobby.HttpLobbyClient;
import org.triplea.swing.DocumentListenerBuilder;
import org.triplea.swing.JButtonBuilder;
import org.triplea.swing.JCheckBoxBuilder;
import org.triplea.swing.SwingComponents;
import org.triplea.swing.jpanel.FlowLayoutBuilder;
import org.triplea.swing.jpanel.GridBagConstraintsAnchor;
import org.triplea.swing.jpanel.GridBagConstraintsBuilder;
import org.triplea.swing.jpanel.GridBagConstraintsFill;
import org.triplea.swing.jpanel.JPanelBuilder;

/** Panel dedicated to changing password after user has logged in with a temporary password. */
public final class ChangePasswordPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private static final String TITLE = "Change Password";
  private @Nullable JDialog dialog;
  private final JPasswordField passwordField = new JPasswordField();
  private final JPasswordField passwordConfirmField = new JPasswordField();
  private final JButton okButton = new JButton("OK");
  private final JCheckBox rememberPassword =
      new JCheckBoxBuilder("Remember Password").bind(ClientSetting.rememberLoginPassword).build();

  public enum AllowCancelMode {
    SHOW_CANCEL_BUTTON,
    DO_NOT_SHOW_CANCEL_BUTTON
  }

  public ChangePasswordPanel(final AllowCancelMode allowCancelMode) {
    setLayout(new BorderLayout());
    final JLabel label = new JLabel(new ImageIcon(Util.getBanner(TITLE)));
    add(label, BorderLayout.NORTH);

    final JPanel main = new JPanel();
    add(main, BorderLayout.CENTER);
    main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    main.setLayout(new GridBagLayout());
    main.add(
        new JLabel("Password:"),
        new GridBagConstraintsBuilder(0, 0)
            .anchor(GridBagConstraintsAnchor.WEST)
            .fill(GridBagConstraintsFill.NONE)
            .insets(5, 0, 0, 0)
            .build());
    main.add(
        passwordField,
        new GridBagConstraintsBuilder(1, 0)
            .weightX(1.0)
            .anchor(GridBagConstraintsAnchor.WEST)
            .fill(GridBagConstraintsFill.HORIZONTAL)
            .insets(5, 5, 0, 0)
            .build());
    main.add(
        new JLabel("Confirm Password:"),
        new GridBagConstraintsBuilder(0, 1)
            .anchor(GridBagConstraintsAnchor.WEST)
            .fill(GridBagConstraintsFill.NONE)
            .insets(5, 0, 0, 0)
            .build());
    main.add(
        passwordConfirmField,
        new GridBagConstraintsBuilder(1, 1)
            .weightX(1.0)
            .anchor(GridBagConstraintsAnchor.WEST)
            .fill(GridBagConstraintsFill.HORIZONTAL)
            .insets(5, 5, 0, 0)
            .build());
    main.add(
        rememberPassword,
        new GridBagConstraintsBuilder(0, 2)
            .anchor(GridBagConstraintsAnchor.WEST)
            .fill(GridBagConstraintsFill.HORIZONTAL)
            .insets(5, 5, 0, 0)
            .build());

    final JPanel buttons =
        new JPanelBuilder()
            .border(BorderFactory.createEmptyBorder(10, 5, 10, 5))
            .flowLayout()
            .flowDirection(FlowLayoutBuilder.Direction.RIGHT)
            .hgap(5)
            .vgap(0)
            .add(okButton)
            .build();

    if (allowCancelMode == AllowCancelMode.SHOW_CANCEL_BUTTON) {
      buttons.add(
          new JButtonBuilder()
              .title("Cancel")
              .actionListener(() -> Optional.ofNullable(dialog).ifPresent(Window::dispose))
              .build());
    }

    add(buttons, BorderLayout.SOUTH);

    okButton.setEnabled(false);
    okButton.addActionListener(e -> close());

    SwingComponents.addEnterKeyListener(this, this::close);

    DocumentListenerBuilder.attachDocumentListener(
        passwordField, () -> okButton.setEnabled(validatePasswords()));
    DocumentListenerBuilder.attachDocumentListener(
        passwordConfirmField, () -> okButton.setEnabled(validatePasswords()));
  }

  private void close() {
    if (dialog != null) {
      dialog.setVisible(false);
    }
  }

  private boolean validatePasswords() {
    return Arrays.equals(passwordField.getPassword(), passwordConfirmField.getPassword())
        && passwordField.getPassword().length > 4;
  }

  /**
   * Shows this panel in a modal dialog.
   *
   * @param parent The dialog parent window.
   * @return New password entered by user, otherwise null if the window is closed.
   */
  private Optional<String> show(final Window parent) {
    dialog = new JDialog(JOptionPane.getFrameForComponent(parent), "", true);
    dialog.getContentPane().add(this);
    SwingComponents.addEscapeKeyListener(dialog, this::close);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
    dialog.dispose();
    dialog = null;
    if (!validatePasswords()) {
      return Optional.empty();
    }

    final char[] password = passwordField.getPassword();
    if (rememberPassword.isSelected()) {
      ClientSetting.lobbySavedPassword.setValueAndFlush(password);
    } else {
      ClientSetting.lobbySavedPassword.resetValue();
    }
    return Optional.of(String.valueOf(password));
  }

  public static boolean doPasswordChange(
      final Window lobbyFrame,
      final HttpLobbyClient lobbyClient,
      final AllowCancelMode allowCancelMode) {
    return new ChangePasswordPanel(allowCancelMode)
        .show(lobbyFrame)
        .map(
            pass -> {
              lobbyClient.getUserAccountClient().changePassword(pass);
              return true;
            })
        .orElse(false);
  }
}
