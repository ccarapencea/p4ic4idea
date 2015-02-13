/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.groboclown.idea.p4ic.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.ui.ListCellRendererWrapper;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.intellij.util.ui.UIUtil;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.ManualP4Config;
import net.groboclown.idea.p4ic.config.P4Config;
import net.groboclown.idea.p4ic.config.P4ConfigUtil;
import net.groboclown.idea.p4ic.ui.connection.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class P4ConfigPanel {
    private JPanel myMainPanel;
    private JComboBox<ConnectionPanel> myConnectionChoice;
    private JButton myRefreshClientList;
    private JComboBox<String> myClientList;
    private JCheckBox myReuseEnvValueCheckBox;
    private JButton checkConnectionButton;
    private JCheckBox mySilentlyGoOfflineOnCheckBox;
    private P4ConfigConnectionPanel myP4ConfigConnectionPanel;
    private ClientPasswordConnectionPanel myClientPasswordConnectionPanel;
    private AuthTicketConnectionPanel authTicketConnectionPanel;
    private SSOConnectionPanel mySSOConnectionPanel;
    private EnvConnectionPanel myEnvConnectionPanel;
    private JPanel myConnectionTypeContainerPanel;
    private JLabel myConnectionDescriptionLabel;
    private JCheckBox mySavePasswordsCheckBox;
    private JLabel myPasswordWarning;
    private RelP4ConfigConnectionPanel myRelP4ConfigPanel;

    private final Project myProject;

    public P4ConfigPanel(@NotNull Project myProject) {
        this.myProject = myProject;

        // Initialize GUI constant values
        $$$setupUI$$$();
        myConnectionChoice.setRenderer(new AuthenticationMethodRenderer());
        myConnectionChoice.setEditable(false);
        // Could add checks to ensure that there is 1 and only 1 connection panel for
        // each connection type
        myConnectionChoice.addItem(myP4ConfigConnectionPanel);
        myConnectionChoice.addItem(myRelP4ConfigPanel);
        myConnectionChoice.addItem(myClientPasswordConnectionPanel);
        myConnectionChoice.addItem(authTicketConnectionPanel);
        myConnectionChoice.addItem(mySSOConnectionPanel);
        myConnectionChoice.addItem(myEnvConnectionPanel);

        // an initial value for connection choice
        myConnectionChoice.setSelectedItem(myEnvConnectionPanel);


        // Initialize GUI listeners
        myConnectionChoice.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                changeConnectionSelection();
            }
        });
        myRefreshClientList.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadClientList();
            }
        });
        myReuseEnvValueCheckBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean allowSelection = !myReuseEnvValueCheckBox.isSelected();
                myRefreshClientList.setEnabled(allowSelection);
                myClientList.setEnabled(allowSelection);
            }
        });
        checkConnectionButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                checkConnection();
            }
        });
    }


    public JPanel getPanel() {
        return myMainPanel;
    }

    public boolean isModified(@NotNull P4Config config) {
        if (config.hasClientnameSet()) {
            if (myReuseEnvValueCheckBox.isSelected()) {
                return true;
            }
            if (!Comparing.equal(config.getClientname(), myClientList.getSelectedItem())) {
                return true;
            }
        }

        if (config.isAutoOffline() != mySilentlyGoOfflineOnCheckBox.isSelected()) {
            return true;
        }

        if (config.isPasswordStoredLocally() != mySavePasswordsCheckBox.isSelected()) {
            return true;
        }

        Object selectedItem = myConnectionChoice.getSelectedItem();
        assert (selectedItem != null && (selectedItem instanceof ConnectionPanel));
        ConnectionPanel connection = (ConnectionPanel) selectedItem;
        if (config.getConnectionMethod() != connection.getConnectionMethod()) {
            return true;
        }
        return connection.isModified(config);
    }

    protected void loadSettingsIntoGUI(@NotNull P4Config config) {
        // ----------------------------------------------------------------
        // non-connection values

        // Client name
        if (config.hasClientnameSet()) {
            myReuseEnvValueCheckBox.setSelected(false);
            myClientList.setEnabled(true);
            myRefreshClientList.setEnabled(true);

            String client = config.getClientname();
            assert client != null;

            // If the client is in the list, don't re-add it.  Otherwise, we need to add it.
            if (!client.equals(myClientList.getSelectedItem())) {
                findClient:
                do {
                    for (int i = 0; i < myClientList.getItemCount(); i++) {
                        Object value = myClientList.getSelectedItem();
                        if (client.equals(value)) {
                            myClientList.setSelectedIndex(i);
                            break findClient;
                        }
                    }

                    // Wasn't found - add it
                    myClientList.addItem(client);
                    // and select it
                    myClientList.setSelectedItem(client);
                } while (false);
            } // else the current client is already selected
        } else {
            myReuseEnvValueCheckBox.setSelected(true);
            myClientList.setEnabled(false);
            myRefreshClientList.setEnabled(false);
        }
        mySilentlyGoOfflineOnCheckBox.setSelected(config.isAutoOffline());
        mySavePasswordsCheckBox.setSelected(config.isPasswordStoredLocally());


        // ----------------------------------------------------------------
        // Dynamic setup for connection information
        for (int i = 0; i < myConnectionChoice.getItemCount(); i++) {
            ConnectionPanel conn = myConnectionChoice.getItemAt(i);
            if (conn.getConnectionMethod() == config.getConnectionMethod()) {
                showConnectionPanel(conn);
                conn.loadSettingsIntoGUI(config);
                myConnectionChoice.setSelectedIndex(i);
            }
        }
    }

    protected void saveSettingsToConfig(@NotNull ManualP4Config config) {
        // Clear out the connection settings so old ones don't interfere
        // with the new ones
        config.setUsername(null);
        config.setPort(null);
        config.setProtocol(null);
        config.setPassword(null);
        config.setAuthTicketPath(null);
        config.setConfigFile(null);
        config.setTrustTicketPath(null);

        ConnectionPanel conn = getSelectedConnection();
        conn.saveSettingsToConfig(config);
        config.setConnectionMethod(conn.getConnectionMethod());

        // ----------------------------------------------------------------
        // non-connection values - overwrite whatever the config panel set

        // Client name
        if (myReuseEnvValueCheckBox.isSelected()) {
            config.setClientname(null);
        } else {
            config.setClientname(getSelectedClient());
        }

        config.setAutoOffline(mySilentlyGoOfflineOnCheckBox.isSelected());
        config.setPasswordStoredLocally(mySavePasswordsCheckBox.isSelected());
    }


    @NotNull
    private ConnectionPanel getSelectedConnection() {
        Object val = myConnectionChoice.getSelectedItem();
        if (val != null && val instanceof ConnectionPanel) {
            return (ConnectionPanel) val;
        }
        throw new IllegalStateException("invalid connection selection");
    }


    @Nullable
    private String getSelectedClient() {
        Object selected = myClientList.getSelectedItem();
        String selectedClient = null;
        if (selected != null) {
            selectedClient = selected.toString();
            if (selectedClient.length() <= 0) {
                selectedClient = null;
            }
        }
        return selectedClient;
    }


    @Nullable
    private P4Config createConnectionConfig() {
        ManualP4Config partial = new ManualP4Config();
        saveSettingsToConfig(partial);
        return P4ConfigUtil.loadCmdP4Config(partial);
    }


    // ---------------------------------------------------------
    // UI callbacks

    private void checkConnection() {
        List<String> clients = new UserClientsLoader(
                myProject, createConnectionConfig()).loadClients();
        if (clients != null) {
            Messages.showMessageDialog(myProject,
                    P4Bundle.message("configuration.dialog.valid-connection.message"),
                    P4Bundle.message("configuration.dialog.valid-connection.title"),
                    Messages.getInformationIcon());
        }
    }

    private void loadClientList() {
        List<String> clients = new UserClientsLoader(
                myProject, createConnectionConfig()).loadClients();
        if (clients == null) {
            // Don't need a status update or any updates; the user should have
            // seen error dialogs.
            return;
        }

        List<String> toAdd = new ArrayList<String>(clients.size() + 1);
        toAdd.addAll(clients);

        // Make sure to keep the currently selected item selected.
        // If it wasn't in the original list, it needs to be added
        // and have a custom renderer highlight it as ss invalid.
        // Also, move the currently selected one to the top.
        Object selected = myClientList.getSelectedItem();
        if (selected != null) {
            clients.remove(selected.toString());
            if (selected.toString().trim().length() > 0) {
                toAdd.add(0, selected.toString());
            }
        }

        myClientList.removeAllItems();
        for (String client : toAdd) {
            myClientList.addItem(client);
        }
        if (selected != null && selected.toString().trim().length() > 0) {
            myClientList.setSelectedItem(selected);
        }
    }

    private void changeConnectionSelection() {
        int currentSelectedIndex = myConnectionChoice.getSelectedIndex();
        ConnectionPanel selected = myConnectionChoice.getItemAt(currentSelectedIndex);
        showConnectionPanel(selected);
    }


    private void showConnectionPanel(@NotNull ConnectionPanel panel) {
        myConnectionDescriptionLabel.setText("<html>" + panel.getDescription());
        ((CardLayout) myConnectionTypeContainerPanel.getLayout()).show(
                myConnectionTypeContainerPanel,
                panel.getConnectionMethod().name());

        // Relative p4config files MUST define their own client name.
        if (panel.getConnectionMethod() == P4Config.ConnectionMethod.REL_P4CONFIG) {
            myReuseEnvValueCheckBox.setSelected(true);
            myReuseEnvValueCheckBox.setEnabled(false);
            checkConnectionButton.setEnabled(false);
        } else {
            myReuseEnvValueCheckBox.setEnabled(true);
            checkConnectionButton.setEnabled(myReuseEnvValueCheckBox.isSelected());
        }
    }


    private void createUIComponents() {
        // Add custom component construction here.
        myP4ConfigConnectionPanel = new P4ConfigConnectionPanel(myProject);
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        myMainPanel = new JPanel();
        myMainPanel.setLayout(new GridLayoutManager(8, 3, new Insets(0, 0, 0, 0), -1, -1));
        final JLabel label1 = new JLabel();
        label1.setHorizontalAlignment(10);
        this.$$$loadLabelText$$$(label1, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.connection-choice"));
        myMainPanel.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        this.$$$loadLabelText$$$(label2, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.clientname"));
        myMainPanel.add(label2, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        myMainPanel.add(panel1, new GridConstraints(3, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel2, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        myClientList = new JComboBox();
        myClientList.setEditable(true);
        panel2.add(myClientList, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
        myMainPanel.add(panel3, new GridConstraints(4, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        myRefreshClientList = new JButton();
        myRefreshClientList.setHorizontalAlignment(0);
        this.$$$loadButtonText$$$(myRefreshClientList, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.choose-client-button"));
        panel3.add(myRefreshClientList, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel3.add(spacer1, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        myReuseEnvValueCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myReuseEnvValueCheckBox, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.clientname.inherit"));
        panel3.add(myReuseEnvValueCheckBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer2 = new Spacer();
        myMainPanel.add(spacer2, new GridConstraints(7, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setHorizontalAlignment(10);
        label3.setHorizontalTextPosition(11);
        this.$$$loadLabelText$$$(label3, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.options"));
        myMainPanel.add(label3, new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        myConnectionTypeContainerPanel = new JPanel();
        myConnectionTypeContainerPanel.setLayout(new CardLayout(0, 0));
        myMainPanel.add(myConnectionTypeContainerPanel, new GridConstraints(2, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        myConnectionTypeContainerPanel.add(myP4ConfigConnectionPanel.$$$getRootComponent$$$(), "P4CONFIG");
        myClientPasswordConnectionPanel = new ClientPasswordConnectionPanel();
        myConnectionTypeContainerPanel.add(myClientPasswordConnectionPanel.$$$getRootComponent$$$(), "CLIENT");
        authTicketConnectionPanel = new AuthTicketConnectionPanel();
        myConnectionTypeContainerPanel.add(authTicketConnectionPanel.$$$getRootComponent$$$(), "AUTH_TICKET");
        mySSOConnectionPanel = new SSOConnectionPanel();
        myConnectionTypeContainerPanel.add(mySSOConnectionPanel.$$$getRootComponent$$$(), "SSO");
        myEnvConnectionPanel = new EnvConnectionPanel();
        myConnectionTypeContainerPanel.add(myEnvConnectionPanel.$$$getRootComponent$$$(), "DEFAULT");
        myRelP4ConfigPanel = new RelP4ConfigConnectionPanel();
        myConnectionTypeContainerPanel.add(myRelP4ConfigPanel.$$$getRootComponent$$$(), "REL_P4CONFIG");
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        myMainPanel.add(panel4, new GridConstraints(0, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        myConnectionChoice = new JComboBox();
        myConnectionChoice.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.connection-choice.picker.tooltip"));
        panel4.add(myConnectionChoice);
        checkConnectionButton = new JButton();
        checkConnectionButton.setHorizontalAlignment(10);
        this.$$$loadButtonText$$$(checkConnectionButton, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.check-connection"));
        panel4.add(checkConnectionButton);
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new BorderLayout(0, 0));
        myMainPanel.add(panel5, new GridConstraints(1, 1, 1, 2, GridConstraints.ANCHOR_NORTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        myConnectionDescriptionLabel = new JLabel();
        myConnectionDescriptionLabel.setText("");
        myConnectionDescriptionLabel.setVerticalAlignment(1);
        panel5.add(myConnectionDescriptionLabel, BorderLayout.CENTER);
        final JPanel panel6 = new JPanel();
        panel6.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
        myMainPanel.add(panel6, new GridConstraints(6, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mySavePasswordsCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(mySavePasswordsCheckBox, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("connection.password.save"));
        panel6.add(mySavePasswordsCheckBox, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final Spacer spacer3 = new Spacer();
        panel6.add(spacer3, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        myPasswordWarning = new JLabel();
        myPasswordWarning.setForeground(new Color(-65536));
        this.$$$loadLabelText$$$(myPasswordWarning, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.password-warning"));
        panel6.add(myPasswordWarning, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 2, false));
        final JPanel panel7 = new JPanel();
        panel7.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        myMainPanel.add(panel7, new GridConstraints(5, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mySilentlyGoOfflineOnCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(mySilentlyGoOfflineOnCheckBox, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.autoconnect"));
        panel7.add(mySilentlyGoOfflineOnCheckBox, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        label1.setLabelFor(myConnectionChoice);
        label2.setLabelFor(myClientList);
    }

    /**
     * @noinspection ALL
     */
    private void $$$loadLabelText$$$(JLabel component, String text) {
        StringBuffer result = new StringBuffer();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) break;
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setDisplayedMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    /**
     * @noinspection ALL
     */
    private void $$$loadButtonText$$$(AbstractButton component, String text) {
        StringBuffer result = new StringBuffer();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) break;
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return myMainPanel;
    }


    private class AuthenticationMethodRenderer extends ListCellRendererWrapper<ConnectionPanel> {
        @Override
        public void customize(JList list, ConnectionPanel value, int index, boolean isSelected, boolean hasFocus) {
            if (isSelected || hasFocus) {
                setBackground(UIUtil.getListSelectionBackground());
                final Color selectedForegroundColor = UIUtil.getListSelectionForeground();
                setForeground(selectedForegroundColor);
            } else {
                setBackground(UIUtil.getListBackground());
                final Color foregroundColor = UIUtil.getListForeground();
                setForeground(foregroundColor);
            }

            if (value == null) {
                setText("");
                setToolTipText("");
            } else {
                setText(value.getName());

                // TODO add a tool tip
                setToolTipText("");
            }
        }
    }

}