package io.camunda.consulting.tools.inspire.bpmnfixer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class InspireBPMNFixer {

  static final Logger logger = LoggerFactory.getLogger(InspireBPMNFixer.class);

  private static final String OUTPUT_FOLDER_NAME = "fixed";
  private static final String QNAME_PREFIX_CHAR = "A";

  private final String[] attributesToFix = {"attachedToRef", "bpmnElement", "default",
    "id", "sourceElement", "sourceRef", "targetElement", "targetRef"};
  private final String[] bpmnFileExtensions = {".bpmn"};

  private List<File> filesToFix = new ArrayList<>();
  private String pathToScan;
  private String outputPath;

  public static void main(String[] args) {
    InspireBPMNFixer fixer = new InspireBPMNFixer();

    if (args.length == 1) {
      fixer.validateQNamePrefix();
      fixer.compileFiles(args);
      fixer.fixFiles();
    } else {
      logger.info("Invalid argument count. Usage: \"InspireBPMNFixer.jar [PATH]\" "
        + "where PATH can be a directory of .bpmn files or a single .bpmn file.");
    }
  }

  private void validateQNamePrefix() {
    if (!QNAME_PREFIX_CHAR.matches(":|_|[A-Z]|[a-z]")) {
      throw new UnsupportedOperationException("QName prefix character " + QNAME_PREFIX_CHAR
        + " is invalid. Allowed characters are [A-Z][a-z]_:");
    }
  }

  public void compileFiles(String[] args) {
    filesToFix = getBpmnFilesFromArgs(args);

    if (filesToFix.isEmpty()) {
      logger.info("Couldn't find any BPMN files to fix");
      System.exit(0);
    } else {
      int fileCount = filesToFix.size();
      logger.info("Found " + fileCount + " file" + ((fileCount > 1) ? "s" : "") + " to fix");
    }
  }

  private List<File> getBpmnFilesFromArgs(String[] args) {
    pathToScan = args[0].replaceAll("\"$", "");;
    File object = new File(pathToScan);

    logger.info("Provided path: \"" + pathToScan + "\"");

    if (object.exists()) {
      if (object.isDirectory()) {
        logger.info("Detecting BPMN files in \"" + pathToScan + "\"");

        outputPath = pathToScan + "\\" + OUTPUT_FOLDER_NAME + "\\";

        try (Stream<Path> paths = Files.list(Paths.get(pathToScan))) {
          filesToFix = paths.filter(
              obj -> Files.isRegularFile(obj) && hasBpmnFileExtension(obj))
            .map(Path::toFile)
            .collect(Collectors.toList());
        } catch (IOException e) {
          logger.error("Error while detecting BPMN files in \"" + pathToScan + "\"");
          System.exit(1);
        }
      } else if (object.isFile() && hasBpmnFileExtension(object.toPath())) {
        pathToScan = object.getParent();
        outputPath = pathToScan + "\\" + OUTPUT_FOLDER_NAME + "\\";

        filesToFix.add(object);
      }
    }

    return filesToFix;
  }

  private boolean hasBpmnFileExtension(Path path) {
    return Arrays.stream(bpmnFileExtensions).anyMatch(fileEnding ->
      path.getFileName().toString().endsWith(fileEnding));
  }

  public void fixFiles() {
    for (File file : filesToFix) {
      Document doc = parseFileAndGetDocument(file);
      NodeList nodes = doc.getElementsByTagName("*");

      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);

        if (node.getNodeType() == Node.ELEMENT_NODE) {
          String tag = node.getNodeName();

          if (tag.equalsIgnoreCase("bpmn2:conditionexpression")) {
            convertExpressionTag(node, "inspire:Rule");
          } else if (tag.equalsIgnoreCase("bpmn2:timeduration")) {
            convertExpressionTag(node, "inspire:StringExpression");
          } else {
            fixDigitPrefixedQNameForTag(node);
            fixDigitPrefixedQNamesForTagAttributes(node);
          }
        }
      }

      writeFixedFile(file, doc);
    }

    logger.info(
      "Fixed file" + ((filesToFix.size() > 1) ? "s have" : " has") + " been written to: \""
        + pathToScan + "\\" + OUTPUT_FOLDER_NAME + "\"");
  }

  private Document parseFileAndGetDocument(File file) {
    Document doc = null;
    try {
      logger.info("Parsing \"" + file.getName() + "\"");
      doc = buildDocument(file);
    } catch (ParserConfigurationException | IOException | SAXException e) {
      logger.error("Error while parsing " + file.getName());
      System.exit(1);
    }

    return doc;
  }

  private Document buildDocument(File file)
    throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = builder.parse(file);

    doc.getDocumentElement().normalize();
    return doc;
  }

  /**
   * Converts a BPMN expression tag from BPM Inspire to Camunda Platform 7.
   *
   * <p>Example for a BPM Inspire expression tag:
   * {@code <bpmn2:conditionExpression xsi:type="inspire:Rule" id="ID" expression="EXPRESSION"/>}
   * <br /> Converted to Camunda Platform 7's format:
   * {@code <bpmn2:conditionExpression
   * xsi:type="bpmn:tFormalExpression">EXPRESSION</bpmn2:conditionExpression>}</p>
   *
   * @param node           The XML node representing the BPMN expression tag
   * @param inspireXsiType The BPM Inspire xsi:type to convert
   */
  private void convertExpressionTag(Node node, String inspireXsiType) {
    NamedNodeMap attributes = node.getAttributes();

    Node expressionAttr = attributes.getNamedItem("expression");
    Node idAttr = attributes.getNamedItem("id");
    Node xsiTypeAttr = attributes.getNamedItem("xsi:type");

    if (expressionAttr != null && idAttr != null && xsiTypeAttr != null) {
      if (xsiTypeAttr.getTextContent().equalsIgnoreCase(inspireXsiType)) {
        String expression = expressionAttr.getTextContent();

        attributes.removeNamedItem("expression");
        attributes.removeNamedItem("id");
        xsiTypeAttr.setNodeValue("bpmn:tFormalExpression");
        node.setTextContent(expression);
      }
    }
  }

  /**
   * Checks and fixes a tag's qualified name starting with a digit.
   *
   * <p>Example for an invalid qualified name: {@code <tag>0123456789</tag>}</p>
   *
   * <p>The found qualified name will be prefixed with the prefix specified in
   * <em>InspireBPMNFixer.QNAME_PREFIX</em>. All qualified names in a file receive the same prefix
   * to keep them consistent.</p>
   *
   * @param node The XML node representing the tag which contains a potentially invalid qualified
   *             name
   */
  private void fixDigitPrefixedQNameForTag(Node node) {
    NamedNodeMap attributes = node.getAttributes();

    if (attributes.getLength() == 0 && node.hasChildNodes()) {
      String textContent = node.getFirstChild().getTextContent()
        .trim().replace("\\n", "").replace("\\r\\n", "");

      // Tag text content starts with a digit
      if (!textContent.isEmpty() && textContent.matches("^[0-9].*$")) {
        node.setTextContent(QNAME_PREFIX_CHAR + textContent);
      }
    }
  }

  /**
   * Checks and fixes a tag's attributes containing qualified names starting with a digit.
   *
   * <p>Example for an invalid qualified name: {@code <tag>0123456789</tag>}</p>
   *
   * <p>The found qualified name will be prefixed with the prefix specified in
   * <em>InspireBPMNFixer.QNAME_PREFIX</em>. All qualified names in a file receive the same prefix
   * to keep them consistent.</p>
   *
   * @param node The XML node representing the tag whose attributes contain potentially invalid
   *             qualified names
   */
  private void fixDigitPrefixedQNamesForTagAttributes(Node node) {
    NamedNodeMap attributes = node.getAttributes();

    for (int j = 0; j < attributes.getLength(); ++j) {
      Node attribute = attributes.item(j);

      String attributeName = attribute.getNodeName();
      List<String> attributeNamesToFix = Arrays.stream(attributesToFix)
        .collect(Collectors.toList());

      if (attributeNamesToFix.contains(attributeName)) {
        String attributeValue = attribute.getNodeValue();

        // Attribute value starts with a digit
        if (attributeValue.matches("^[0-9].*$")) {
          attribute.setNodeValue(QNAME_PREFIX_CHAR + attributeValue);

          // Attribute value is negative
        } else if (attributeValue.matches("^-.*")) {
          attribute.setNodeValue(QNAME_PREFIX_CHAR + attributeValue.substring(1));
        }
      }
    }
  }

  private void writeFixedFile(File file, Document doc) {
    logger.info("Writing fixed file to \"" + OUTPUT_FOLDER_NAME + "\\" + file.getName() + "\"");

    try {
      var outputFolder = new File(outputPath);
      if (!outputFolder.exists()) {
        if (!outputFolder.mkdir()) {
          logger.error("Error while creating output folder: " + outputPath);
        }
      }

      FileOutputStream out = new FileOutputStream(outputPath + file.getName());
      transformDocument(doc, new BufferedOutputStream(out));
    } catch (IOException | TransformerException e) {
      logger.error("Error while writing fixed BPMN file: " + file.getName());
      System.exit(1);
    }
  }

  private void transformDocument(Document doc, OutputStream out)
    throws TransformerException {
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
    transformer.setOutputProperty(OutputKeys.INDENT, "no");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

    transformer.transform(new DOMSource(doc),
      new StreamResult(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
  }
}