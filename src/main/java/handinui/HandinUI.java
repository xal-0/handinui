package handinui;

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
import java.text.MessageFormat;

class HandinUI {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            /* don't care if we can't set the L&F */
        }
        var frame = new JFrame("HandinUI");
        var panel = new HandinUI(frame);

        frame.setContentPane(panel.mainPanel);
        frame.setSize(300, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private HandinUI(JFrame frame) {
        mainPanel.setLayout(new GridBagLayout());
        var cc = new GridBagConstraints();
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.weightx = 1;
        mainPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        var c = new GridBagConstraints();

        var serverPanel = new JPanel();
        serverPanel.setLayout(new GridBagLayout());
        cc.gridy = 0;
        mainPanel.add(serverPanel, cc);
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
        cc.gridy = 1;
        mainPanel.add(credentialsPanel, cc);
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
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        credentialsPanel.add(testButton, c);

        usernameField.getDocument().addDocumentListener(new SubmitEnabledListener());
        passwordField.getDocument().addDocumentListener(new SubmitEnabledListener());

        testButton.addActionListener(e -> new SSHTestWorker().execute());

        var coursePanel = new JPanel(new GridBagLayout());
        cc.gridy = 2;
        mainPanel.add(coursePanel, cc);
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
        cc.gridy = 3;
        mainPanel.add(submitPanel, cc);
        c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 5, 5, 5);
        JButton selectDirectoryButton = new JButton("Select directory...");
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
        cc.gridy = 4;
        cc.fill = GridBagConstraints.BOTH;
        cc.weighty = 1;
        mainPanel.add(submitDialogPanel, cc);
        submitProgressBar.setEnabled(false);
        submitButton.setEnabled(false);

        submitButton.addActionListener(e -> new SubmitWorker().execute());

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
        boolean creds = !usernameField.getText().equals("") && passwordField.getPassword().length != 0;
        boolean fetch = creds && !courseCodeTextField.getText().equals("");
        boolean submit = selectedDirectory != null && selectedDirectory.exists()
                && assignmentComboBox.getSelectedItem() != null
                && selectedDirectory.isDirectory() & creds;
        submitButton.setEnabled(submit);
        fetchAssignmentsButton.setEnabled(fetch);
        testButton.setEnabled(creds);
    }

    private ByteArrayOutputStream zipSelected() throws IOException {
        var outstream = new ByteArrayOutputStream();
        var total = (int) Files.walk(selectedDirectory.toPath())
                .count() - 1;
        final int[] count = {0};
        ZipUtil.pack(selectedDirectory, outstream, name -> {
            count[0]++;
            submitProgressBar.setValue(count[0] * 1000 / total / 2);
            return name;
        });
        return outstream;
    }

    private void submitEnabled(ActionEvent e) {
        submitEnabled();
    }

    private String getCourseCode() {
        return courseCodeTextField.getText()
                .toLowerCase()
                .strip()
                .replace("-", "")
                .replace(" ", "");
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
                        return transferred -> submitProgressBar.setValue((int) (500 + transferred * 1000 / total / 2));
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
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(zipstream);
                    }
                };
                transfer.upload(source, ".handinui/source.zip");
                submitProgressBar.setValue(1000);
                session.close();
                session = client.startSession();
                cmd = session.exec(MessageFormat.format("rm -rf {0}/{1}; mkdir -p {0}; chmod 0700 {0}; unzip -o -d {0}/{1} .handinui/source.zip",
                        getCourseCode(),
                        assignmentComboBox.getSelectedItem()));
                cmd.join();
                session.close();
                session = client.startSession();
                cmd = session.exec(MessageFormat.format("handin -p -o {0} {1}", getCourseCode(), assignmentComboBox.getSelectedItem()));
                cmd.join();
                updateLog(cmd);
                session.close();
                session = client.startSession();
                cmd = session.exec(MessageFormat.format("handin -c {0} {1}", getCourseCode(), assignmentComboBox.getSelectedItem()));
                cmd.join();
                updateLog(cmd);
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

        private void updateLog(Session.Command cmd) throws IOException {
            submitLog.setText(submitLog.getText() + new String(cmd.getErrorStream().readAllBytes()));
            submitLog.setText(submitLog.getText() + new String(cmd.getInputStream().readAllBytes()));
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
                var cmd = session.exec("handin -l '" + getCourseCode() + "'");
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

    private final JPanel mainPanel = new JPanel();
    private final JProgressBar submitProgressBar = new JProgressBar(0, 1000);
    private final JTextArea submitLog = new JTextArea();
    private final JComboBox<String> serverComboBox = new JComboBox<>();
    private final JPasswordField passwordField = new JPasswordField();
    private final JTextField usernameField = new JTextField();
    private JCheckBox saveCredentialsCheckBox = new JCheckBox("Save credentials");
    private final JButton testButton = new JButton("Test");
    private final JButton fetchAssignmentsButton = new JButton("Fetch assignments");
    private final JTextField courseCodeTextField = new JTextField();
    private final JComboBox<String> assignmentComboBox = new JComboBox<>();
    private final JButton submitButton = new JButton("Submit");
    private final JLabel directoryLabel = new JLabel("");
}

