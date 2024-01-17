
/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package MizanRsync;

import java.io.BufferedReader;
import java.io.File;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 *
 * @author mulyadi
 */
public class Main {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

        String os = System.getProperty("os.name");

        Properties prop = new Properties();
        try {
            if (os.toLowerCase().contains("windows")) {
                prop.load(new FileReader("config.properties"));
            } else {
                prop.load(new FileReader("config.properties"));
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        String host = prop.getProperty("rsync.host");
        String apiPort = prop.getProperty("rsync.apiPort");
        String apiCompanyCode = prop.getProperty("rsync.apiCompanyCode");
        String username = prop.getProperty("rsync.username");
        String password = prop.getProperty("rsync.password");
        String serverPath = prop.getProperty("rsync.serverpath");
        String localPath = prop.getProperty("rsync.localpath");
        String intervalString = prop.getProperty("rsync.interval");
        int interval = 1000 * Integer.parseInt(intervalString);
        String preCommand = prop.getProperty("rsync.preCommand");
        String databaseConnection = prop.getProperty("rsync.databaseConnection");

        ExecutorService serv = Executors.newSingleThreadExecutor();
        serv.execute(() -> {
            while (true) {
                try {
                    boolean isBackupSuccess = false;

                    File flog = new File("backup.log");
                    flog.delete();

                    if (!preCommand.equals("none")) {
                        Runtime rt = Runtime.getRuntime();
                        Process p = rt.exec(preCommand);

                        try ( BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                            String result = br.lines().collect(Collectors.joining(System.lineSeparator()));

                            if (flog.exists()) {
                                BufferedReader brlog = new BufferedReader(new FileReader(flog));
                                String log;
                                while ((log = brlog.readLine()) != null) {
                                    if (log.contains("gbak:closing file, committing, and finishing")) {
                                        isBackupSuccess = true;
                                    } else {
                                        isBackupSuccess = false;
                                    }
                                }
                                brlog.close();
                            }
                        }

                        try ( BufferedReader brError = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                            String resultError = brError.lines().collect(Collectors.joining(System.lineSeparator()));
                            System.out.println(resultError);
                        }
                    }

                    if (isBackupSuccess) {
                        boolean isReadySync = new Main().getIsActiveSync(apiPort, apiCompanyCode);
                        if (isReadySync) {

                            Logger.getLogger(Main.class.getName()).info("Ready to sync");

                            //String command = "sshpass -p'{{password}}' rsync -avzP {{localPath}} {{username}}@{{host}}:{{serverPath}}";
                            String command = "/home/mizanbackup/exec";

                            if (os.toLowerCase().contains("windows")) {
                                command = "cmd.exe /c wsl sshpass -p '{{password}}' rsync -avzP {{localPath}} {{username}}@{{host}}:{{serverPath}}";
                            }

                            command = command.replace("{{password}}", password)
                                    .replace("{{localPath}}", localPath)
                                    .replace("{{username}}", username)
                                    .replace("{{host}}", host)
                                    .replace("{{serverPath}}", serverPath);

                            String strip = Stream.iterate(0, x -> x + 1).limit(command.length()).map(d -> "-").collect(Collectors.joining());

                            System.out.println("\n");
                            System.out.println(strip);
                            System.out.println(command);
                            System.out.println(strip);

                            Runtime rt = Runtime.getRuntime();
                            Process p = rt.exec(command);

                            try ( BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                                String result = br.lines().collect(Collectors.joining(System.lineSeparator()));
                                System.out.println(result);
                            }

                            try ( BufferedReader brError = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                                String resultError = brError.lines().collect(Collectors.joining(System.lineSeparator()));
                                System.out.println(resultError);
                            }
                        } else {
                            Logger.getLogger(Main.class.getName()).info("Sync Not Active");
                        }
                    } else {

                        try ( Connection connection = DriverManager.getConnection(databaseConnection, "SYSDBA", "masterkey");  Statement st = connection.createStatement()) {
                            st.addBatch("CREATE EXCEPTION GAGAL_BACKUP_DATABASE 'Gagal Backup Database. Silahkan Buka Data Backup Atau Repair Database Anda.'");
                            st.addBatch("CREATE trigger genjur_gagal_backup for genjur active before insert position 0 AS begin exception gagal_backup_database;end;");
                            st.executeBatch();
                            System.out.println("Database gagal di backup dan proses transaksi akan diblok");
                        } catch (SQLException ex) {
                            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                        } 

                    }

                } catch (IOException | org.json.simple.parser.ParseException ex) {
                    Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                }

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ex) {
                    Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

        });
        serv.shutdown();
    }

    public boolean getIsActiveSync(String port, String companyCode) throws org.json.simple.parser.ParseException {
        try {
            URL url = new URL("http://mizancloud.com:" + port + "/biling/user-layanan/check-active-backup-service?companyCode=" + companyCode);
            Logger.getLogger(Main.class.getName()).info(url.toString());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            InputStream is = connection.getInputStream();
            String result = new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining(System.lineSeparator()));
            JSONParser parser = new JSONParser();
            JSONObject obj = (JSONObject) parser.parse(result);
            Map<String, Object> data = (Map) obj.get("data");
            boolean isActiveCloud = (boolean) data.get("isActiveCloud");
            boolean isActiveBackup = (boolean) data.get("isActiveBackup");
            if (isActiveCloud == false && isActiveBackup == true) {
                return true;
            }
            return false;
        } catch (MalformedURLException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }

}
