import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.userauth.UserAuthException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class HandinUI {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
        } catch (Exception e) {
            /* don't care if we can't set the L&F */
        }
        var frame = new JFrame("HandinUI");
        var panel = new HandinUI();

        frame.setContentPane(panel.mainPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    HandinUI() {
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS));
        mainPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        var c = new GridBagConstraints();

        var serverPanel = new JPanel();
        serverPanel.setLayout(new GridBagLayout());
        mainPanel.add(serverPanel);
        c.gridx = 0;
        c.insets = new Insets(5, 5, 5, 5);
        serverPanel.add(new JLabel("UBC Server"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        serverPanel.add(serverComboBox, c);
        serverComboBox.addItem("ssh.ece.ubc.ca");

        c = new GridBagConstraints();
        var credentialsPanel = new JPanel(new GridBagLayout());
        mainPanel.add(credentialsPanel);
        c.insets = new Insets(5, 5, 5, 5);
        credentialsPanel.add(new JLabel("Username"), c);
        c.gridy = 1;
        credentialsPanel.add(new JLabel("Password"), c);
        c.gridx = 1;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        credentialsPanel.add(usernameField, c);
        c.gridy = 1;
        credentialsPanel.add(passwordField, c);
        c.gridx = 2;
        c.gridy = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_START;
        credentialsPanel.add(saveCredentialsCheckBox, c);
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        credentialsPanel.add(testButton, c);

        testButton.addActionListener(e -> new SSHTestWorker().execute());

        var coursePanel = new JPanel(new GridBagLayout());
        mainPanel.add(coursePanel);
        c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        coursePanel.add(new JLabel("Course code"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        coursePanel.add(courseCodeTextField, c);
        c.gridy = 1;
        coursePanel.add(assignmentComboBox, c);
        c.gridx = 0;
        c.weightx = 0;
        coursePanel.add(fetchAssignmentsButton, c);

        fetchAssignmentsButton.addActionListener(e -> new FetchAssignmentsWorker().execute());

        var submitPanel = new JPanel(new GridBagLayout());
        mainPanel.add(submitPanel);
        c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);
        submitPanel.add(selectDirectoryButton, c);
        c.gridx = 2;
        submitPanel.add(submitButton, c);
        c.gridx = 1;
        c.weightx = 1;
        submitPanel.add(directoryLabel, c);

        selectDirectoryButton.addActionListener(e -> {
            var f = new JFileChooser();
            f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            f.showOpenDialog(mainPanel);
            selectedDirectory = f.getSelectedFile();
            directoryLabel.setText(selectedDirectory.getAbsolutePath());
        });

        submitButton.addActionListener(e -> new SubmitWorker().execute());
    }

    class SubmitWorker extends SwingWorker<Object, Object> {
        @Override
        protected Object doInBackground() {
            setConnectButtonsEnabled(false);
            try {
                startSession();
                var transfer = client.newSCPFileTransfer();
                transfer.upload("/home/sjs/hello", "out");
                stopSession();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(mainPanel, e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                return null;
            } finally {
                setConnectButtonsEnabled(true);
            }

            return null;
        }
    }

    class SSHTestWorker extends SwingWorker<Object, Object> {
        @Override
        protected Object doInBackground() {
            setConnectButtonsEnabled(false);
            try {
                startSession();
                stopSession();
            } catch (UserAuthException e) {
                JOptionPane.showMessageDialog(mainPanel, "Incorrect credentials", "Error",
                        JOptionPane.ERROR_MESSAGE);
                return null;
            } catch (IOException e) {
                JOptionPane.showMessageDialog(mainPanel, e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                return null;
            } finally {
                setConnectButtonsEnabled(true);
            }

            JOptionPane.showMessageDialog(mainPanel, "Test succeeded", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
    }

    class FetchAssignmentsWorker extends SwingWorker<Object, Object> {
        @Override
        protected Object doInBackground() {
            setConnectButtonsEnabled(false);
            try {
                startSession();
                var cmd = session.exec("handin -l '" + courseCodeTextField.getText() + "'");
                cmd.join();
                var text = new String(cmd.getInputStream().readAllBytes());
                assignmentComboBox.removeAllItems();
                text.lines().forEach(s -> assignmentComboBox.addItem(s.split(" ")[1]));
                if (assignmentComboBox.getItemCount() < 1) {
                    JOptionPane.showMessageDialog(mainPanel, "Error fetching assignments",
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
                stopSession();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(mainPanel, "Error fetching assignments: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return null;
            } finally {
                setConnectButtonsEnabled(true);
            }
            return null;
        }
    }

    private void setConnectButtonsEnabled(boolean en) {
        testButton.setEnabled(en);
        fetchAssignmentsButton.setEnabled(en);
        submitButton.setEnabled(en);
    }

    private void startSession() throws IOException {
        client = new SSHClient();
        client.addHostKeyVerifier((hostname, port, key) -> true);
        client.connect("ssh.ece.ubc.ca");
        client.authPassword(usernameField.getText(), passwordField.getPassword());
        session = client.startSession();
    }

    private void stopSession() throws IOException {
        session.close();
        client.disconnect();
    }

    private SSHClient client;
    private Session session;

    private File selectedDirectory;

    private JPanel mainPanel = new JPanel();
    private JComboBox serverComboBox = new JComboBox();
    private JPasswordField passwordField = new JPasswordField();
    private JTextField usernameField = new JTextField();
    private JCheckBox saveCredentialsCheckBox = new JCheckBox("Save credentials");
    private JButton testButton = new JButton("Test");
    private JButton fetchAssignmentsButton = new JButton("Fetch assignments");
    private JTextField courseCodeTextField = new JTextField();
    private JComboBox assignmentComboBox = new JComboBox();
    private JButton selectDirectoryButton = new JButton("Select directory...");
    private JButton submitButton = new JButton("Submit");
    private JLabel directoryLabel = new JLabel("");
}

