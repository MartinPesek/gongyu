package co.orbu.controller;

import co.orbu.parser.AutoDetectParser;
import co.orbu.utils.MimeTypeExtension;
import co.orbu.utils.StringGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Controller
public class UploadController {

    @Value("${ofs.dirUploadPath}")
    private String dirUploadPath;

    @Value("${ofs.baseImageUrl}")
    private String baseImageUrl;

    @Value("${ofs.maxFileDownloadSize}")
    private long maxFileDownloadSize;

    /**
     * Saves Base64 string as binary file.
     * @param   data    Base64 string.
     * @return  File name of saved file or null if no file was saved.
     * @throws  IOException
     */
    private String saveBase64ToFile(String data) throws IOException {
        String[] mimeTypeBase64 = data.split(";");

        String base64Data = null;
        String extension = null;

        for (String s : mimeTypeBase64) {
            int colonIndex = s.indexOf(':');
            if (colonIndex == -1) {
                if (s.toLowerCase().startsWith("base64,"))
                    base64Data = s.substring(6).replace(' ', '+');

                continue;
            }

            String[] header = s.split(":");
            if (!header[0].toLowerCase().equals("data"))
                continue;

            extension = MimeTypeExtension.getExtensionFromMimeType(header[1]);
        }

        // unable to parse data, do nothing
        if (base64Data == null || base64Data.isEmpty() || extension == null || extension.isEmpty()) {
            return null;
        }

        // TODO: error/exception handling
        // TODO: this code sucks... Proof of concept though -- improve!

        byte[] rawData = DatatypeConverter.parseBase64Binary(base64Data);
        File file = new File(new File(dirUploadPath), StringGenerator.getRandomString() + extension);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.write(rawData, 0, rawData.length);
        }

        return file.getName();
    }

    /**
     * Downloads file from web and saves it to file.
     * @param   url    URL of a file to download.
     * @return  File name of saved file or null if no file was saved.
     */
    private String saveURLToFile(String url) {
        File file = new File(new File(dirUploadPath), StringGenerator.getRandomString());

        try {
            URL fileUrl = new URL(url);

            try (ReadableByteChannel rbc = Channels.newChannel(fileUrl.openStream()))
            {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.getChannel().transferFrom(rbc, 0, maxFileDownloadSize);
                }
            }

            String extension = null;

            AutoDetectParser adp = new AutoDetectParser(file);
            if (adp.isKnownType()) {
                extension = MimeTypeExtension.getExtensionFromMimeType(adp.getMimeType());
            }

            if (extension != null) {
                // rename file with proper extension
                File newFilename = new File(file.getPath() + extension);
                Files.move(file.toPath(), newFilename.toPath(), StandardCopyOption.REPLACE_EXISTING);
                file = newFilename;
            } else {
                // unknown file, delete it
                Files.delete(file.toPath());
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        return file.getName();
    }

    @RequestMapping(method = RequestMethod.POST)
    @ResponseBody
    public String saveImage(@ModelAttribute(value = "data") String data, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (data == null || data.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "No data received.";
        }

        String filename;
        if (data.startsWith("http://")) {
            String urlParameters = "";

            Map<String, String[]> parameters = request.getParameterMap();
            if (parameters.size() > 1) {
                for (Map.Entry<String, String[]> p : parameters.entrySet()) {
                    String key = p.getKey();
                    String[] values = p.getValue();

                    if (key.startsWith("data") || values.length == 0)
                        continue;

                    urlParameters += "&" + URLEncoder.encode(p.getKey(), "UTF-8") + "=" + URLEncoder.encode(p.getValue()[0], "UTF-8");
                }
            }

            filename = saveURLToFile(data + urlParameters);
        } else {
            filename = saveBase64ToFile(data);
        }

        if (filename == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "Unable to parse data.";
        }

        return baseImageUrl + filename;
    }
}