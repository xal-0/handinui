import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.xfer.InMemorySourceFile;
import net.schmizz.sshj.xfer.TransferListener;
import org.zeroturnaround.zip.NameMapper;
import org.zeroturnaround.zip.ZipUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.ZipOutputStream;

public class HandinUI {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            /* don't care if we can't set the L&F */
        }
        var frame = new JFrame("HandinUI");
        var panel = new HandinUI(frame);

        frame.setContentPane(panel.mainPanel);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    HandinUI(JFrame frame) {
        this.frame = frame;
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

        usernameField.getDocument().addDocumentListener(new SubmitEnabledListener());
        passwordField.getDocument().addDocumentListener(new SubmitEnabledListener());

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
        assignmentComboBox.addActionListener(this::submitEnabled);
        courseCodeTextField.getDocument().addDocumentListener(new SubmitEnabledListener());

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
            if (f.getSelectedFile() == null) {
                submitEnabled();
                return;
            }
            selectedDirectory = f.getSelectedFile();
            directoryLabel.setText(selectedDirectory.getAbsolutePath());
            submitEnabled();
        });

        var submitDialogPanel = new JPanel();
        submitDialogPanel.setLayout(new BorderLayout());
        submitLog.setEditable(false);
        submitDialogPanel.add(submitProgressBar, BorderLayout.SOUTH);
        submitDialogPanel.add(new JScrollPane(submitLog), BorderLayout.CENTER);
        mainPanel.add(submitDialogPanel);
        submitProgressBar.setEnabled(false);
        submitButton.setEnabled(false);

        submitButton.addActionListener(e -> {
            new SubmitWorker().execute();
        });

        submitEnabled();
    }

    class SubmitEnabledListener implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent documentEvent) {
            submitEnabled();
        }

        @Override
        public void removeUpdate(DocumentEvent documentEvent) {
            submitEnabled();
        }

        @Override
        public void changedUpdate(DocumentEvent documentEvent) {
            submitEnabled();
        }
    }

    private void submitEnabled() {
        boolean creds = !usernameField.getText().equals("") && !passwordField.getText().equals("");
        boolean fetch = creds && !courseCodeTextField.getText().equals("");
        boolean submit = selectedDirectory != null && selectedDirectory.exists()
                && assignmentComboBox.getSelectedItem() != null
                && selectedDirectory.isDirectory() & creds;
        submitButton.setEnabled(submit);
        fetchAssignmentsButton.setEnabled(fetch);
        testButton.setEnabled(creds);
    }

    ByteArrayOutputStream zipSelected() throws IOException {
        var outstream = new ByteArrayOutputStream();
        var total = (int) Files.walk(selectedDirectory.toPath())
                .count() - 1;
        final int[] count = {0};
        ZipUtil.pack(selectedDirectory, outstream, new NameMapper() {
            @Override
            public String map(String name) {
                count[0]++;
                submitProgressBar.setValue(count[0] * 1000 / total / 2);
                return name;
            }
        });
        return outstream;
    }

    private void submitEnabled(ActionEvent e) {
        submitEnabled();
    }

    class SubmitWorker extends SwingWorker<Object, Object> {
        @Override
        protected Object doInBackground() {
            submitLog.setText("");
            submitProgressBar.setValue(0);
            setConnectButtonsEnabled(false);
            try {
                startSession();
                var cmd = session.exec("mkdir -p .handinui; chmod 700 .handinui; rm .handinui/source.zip -f");
                cmd.join();
                var zipstream = zipSelected().toByteArray();
                var transfer = client.newSFTPClient().getFileTransfer();
                long total = zipstream.length;
                transfer.setTransferListener(new TransferListener() {
                    @Override
                    public TransferListener directory(String name) {
                        return this;
                    }

                    @Override
                    public StreamCopier.Listener file(String name, long size) {
                        return transferred -> {
                            submitProgressBar.setValue((int) (500 + transferred * 1000 / total / 2));
                        };
                    }
                });
                InMemorySourceFile source = new InMemorySourceFile() {
                    @Override
                    public String getName() {
                        return "work.zip";
                    }

                    @Override
                    public long getLength() {
                        return total;
                    }

                    @Override
                    public InputStream getInputStream() throws IOException {
                        return new ByteArrayInputStream(zipstream);
                    }
                };
                transfer.upload(source, ".handinui/source.zip");
                submitProgressBar.setValue(1000);
                session.close();
                session = client.startSession();
                cmd = session.exec("mkdir -p " + courseCodeTextField.getText()
                        + "; chmod 0700 " + courseCodeTextField.getText()
                        + "; unzip -o -d " + courseCodeTextField.getText() + " .handin/source.zip"
                        + "/" + (String) assignmentComboBox.getSelectedItem());
                cmd.join();
                session.close();
                session = client.startSession();
                cmd = session.exec("handin -p -o "
                        + courseCodeTextField.getText()
                        + " "
                        + assignmentComboBox.getSelectedItem());
                cmd.join();
                submitLog.setText(new String(cmd.getErrorStream().readAllBytes()));
                session.close();
                session = client.startSession();
                cmd = session.exec("handin -c "
                        + courseCodeTextField.getText()
                        + " "
                        + assignmentComboBox.getSelectedItem());
                cmd.join();
                submitLog.setText(submitLog.getText() + new String(cmd.getErrorStream().readAllBytes()));
                stopSession();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(mainPanel, e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                return null;
            } finally {
                submitProgressBar.setEnabled(false);
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
        if (en) {
            submitEnabled();
        }
    }

    private void startSession() throws IOException {
        client = new SSHClient();
        client.addHostKeyVerifier((hostname, port, key) -> true);
        client.connect((String) serverComboBox.getSelectedItem());
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

    private JFrame frame;
    private JPanel mainPanel = new JPanel();
    private JProgressBar submitProgressBar = new JProgressBar(0, 1000);
    private JTextArea submitLog = new JTextArea();
    private JComboBox<String> serverComboBox = new JComboBox<>();
    private JPasswordField passwordField = new JPasswordField();
    private JTextField usernameField = new JTextField();
    private JCheckBox saveCredentialsCheckBox = new JCheckBox("Save credentials");
    private JButton testButton = new JButton("Test");
    private JButton fetchAssignmentsButton = new JButton("Fetch assignments");
    private JTextField courseCodeTextField = new JTextField();
    private JComboBox<String> assignmentComboBox = new JComboBox<>();
    private JButton selectDirectoryButton = new JButton("Select directory...");
    private JButton submitButton = new JButton("Submit");
    private JLabel directoryLabel = new JLabel("");
}

