package com.kmcguire.KDepend;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarFile;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;

public class Loader {
    private static final File               cacheDir;
    
    static {
        cacheDir = new File(".\\kdepend.cache\\");
        
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }        
    }
    
    public static byte[] getURLContents(String url)
            throws IOException, MalformedURLException {
        URLConnection                   conn;
        InputStream                     response;
        byte[]                          bbuf;
        ByteArrayOutputStream           bb;
        int                             cnt;
        
        conn = new URL(url).openConnection();
        conn.setDoOutput(false); // GET
        response = conn.getInputStream();

        bb = new ByteArrayOutputStream();
        bbuf = new byte[1024];

        while ((cnt = response.read(bbuf)) > -1) {
            bb.write(bbuf, 0, cnt);
        }
        
        return bb.toByteArray();
    }
    
    public static String getBetween(String contents, int offset, String start, String end) {
        int         s, e;
        
        s = contents.indexOf(start, offset);
        
        if (end == null) {
            if (s < 0) {
                return null;
            }
            return contents.substring(s + start.length());
        }
        
        e = contents.indexOf(end, s);
        
        if (s < 0 || e < 0 || e < s) {
            return null;
        }
        
        return contents.substring(s + start.length(), e);
    }
    
    /**
     * We have to navigate through the DBO HTML content to find the exact
     * build-string specified, then navigate to that build page to find
     * the download URL for the package file.
     * 
     * @param projectName                   bukkit project name
     * @param buildString                   bukkit build string (file name)
     * @return                              url to download package with
     * @throws IOException                  
     * @throws MalformedURLException 
     */
    public static String getBukkitProjectPackageURL(String projectName, String buildString)
                throws IOException, MalformedURLException {
        String                      url;
        byte[]                      _contents;
        String                      contents;        
        int                         oddi, eveni;
        int                         offset;
        String                      part;
        String                      p_build, p_type, p_status, p_filename, p_dlpagelink;
        
        url = String.format("http://dev.bukkit.org/server-mods/%s/files/", projectName);
        
        _contents = getURLContents(url);
        contents = new String(_contents);

        offset = 0;
        while (true) {
            oddi = contents.indexOf("<tr class=\"odd\">", offset);
            eveni = contents.indexOf("<tr class=\"even\">", offset);
            
            /* exit the loop no more entries left */
            if (oddi > eveni || oddi < 0) {
                if (eveni < 0) {
                    break;
                }
                oddi = eveni;
            }
            
            offset = oddi + 10;
            
            eveni = contents.indexOf("</tr>", oddi);
            /* something is corrupted or format changed */
            if (eveni < 0) {
                break;
            }
            
            part = contents.substring(oddi, eveni);
            
            // /server-mods/kfactions/files/35-1-18-40b/">1.18.40B
            p_build = getBetween(part, 0, "<td class=\"col-file\"><a href=\"", "</a></td>");
            p_dlpagelink = getBetween(p_build, 0, "/server-mods/kfactions/files/", "\"");
            p_build = getBetween(p_build, 0, "\">", null);
            
            p_type = getBetween(part, 0, "<td class=\"col-type\"><span class=\"file-type file-type-r\">", "</span></td>");
            p_status = getBetween(part, 0, "<td class=\"col-status\"><span class=\"file-status file-status-s\">", "</span></td>");
            p_filename = getBetween(part, 0, "<td class=\"col-filename\">", "</td>");
            
            /*
             * Either download the first one (most recent) or download the one specified. I hope to
             * add better logic to choose between Beta, Release, and such later.
             */
            if (buildString == null || p_build.toLowerCase().equals(buildString.toLowerCase())) {
                url = String.format("http://dev.bukkit.org/server-mods/%s/files/%s", projectName, p_dlpagelink);
                contents = new String(getURLContents(url));                

                url = getBetween(contents, 0, "<a href=\"http://dev.bukkit.org/media/files/", "\">");
                url = String.format("http://dev.bukkit.org/media/files/%s", url);
                return url;
            }
        }
        
        
        return null;
    }
    
    public static void fetchBukkitProject(Server server, String projectName, String buildString) 
            throws MalformedURLException, IOException {
        RandomAccessFile            raf;
        File                        file;
        byte[]                      contents;
        String                      url;
        
        server.getLogger().info(String.format("[LOOKING] %s-%s", projectName, buildString));
        url = getBukkitProjectPackageURL(projectName, buildString);
        
        server.getLogger().info(String.format("[FETCHING] %s-%s", projectName, buildString));
        contents = getURLContents(url);
        
        file = new File(cacheDir, String.format("%s-%s.jar", projectName, buildString));
        server.getLogger().info(String.format("[OK] %s-%s", projectName, buildString));
        
        if (file.exists()) {
            file.delete();
        }
        
        file.createNewFile();
        
        raf = new RandomAccessFile(file, "rw");
        raf.write(contents);
        raf.close();
    }
    
    public static boolean loadBukkitProjectPlugin(Server server, String projectName, String buildString) 
            throws MalformedURLException, IOException, InvalidPluginException, 
                   InvalidDescriptionException {
        File                    file;
        Plugin                  plugin;
        JarFile                 jf;
        YamlConfiguration       cfg;
        String                  pluginName;
        
        if (buildString.length() < 1) {
            buildString = "latest";
        }
        
        file = new File(cacheDir, String.format("%s-%s.jar", projectName, buildString));
        
        if (!file.exists()) {
            fetchBukkitProject(server, projectName, buildString);
        } else {
            server.getLogger().info(String.format("[CACHED] %s-%s", projectName, buildString));
        }
        
        if (!file.exists()) {
            server.getLogger().info(String.format("[FETCH-FAILED] %s-%s", projectName, buildString));
            return false;
        }
        
        // load the JAR into memory
        // pluginManager.loadPlugin
        jf = new JarFile(file);
        
        cfg = new YamlConfiguration();
        try {
            cfg.load(jf.getInputStream(jf.getEntry("plugin.yml")));
        } catch (InvalidConfigurationException ex) {
            server.getLogger().info(String.format("[BAD CONFIG.YML] %s-%s", projectName, buildString));
            return false;
        }
        
        pluginName = cfg.getString("name");
        
        server.getLogger().info(String.format("[LOADED] %s-%s", projectName, buildString));

        for (Plugin p : server.getPluginManager().getPlugins()) {
            if (p.getName().equals(pluginName)) {
                /* problem, plugin has already been loaded and enabled */
                server.getLogger().info(String.format("[ALREADY LOADED] %s-%s", projectName, buildString));                
                return false;
            }
        }
        
        plugin = server.getPluginManager().loadPlugin(file);
        server.getPluginManager().enablePlugin(plugin);
        return true;
    }
    
    public static byte[] loadResource(String path) throws IOException {
        InputStream             ins;
        byte[]                  sbuf;
        ByteArrayOutputStream   buf;
        int                     count;
        
        ins = Loader.class.getClassLoader().getResourceAsStream(path);
        
        if (ins == null) {
            throw new IOException(String.format("I/O exception trying to read %s.", path));
        }
        
        buf = new ByteArrayOutputStream();
        sbuf = new byte[1024];
        
        while ((count = ins.read(sbuf)) > 0) {
            buf.write(sbuf, 0, count);
        }
        
        ins.close();
        
        return buf.toByteArray();
    }    
}
