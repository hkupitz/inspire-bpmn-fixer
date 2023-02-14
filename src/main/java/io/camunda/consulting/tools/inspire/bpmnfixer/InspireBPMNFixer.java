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
  private static final String QNAME_PREFIX_CHAR = "_";

  private final String[] attributesToFix = {"attachedToRef", "bpmnElement", "default",
    "id", "sourceElement", "sourceRef", "targetElement", "targetRef"};
  private final String[] bpmnFileExtensions = {".bpmn"};

  private List<File> filesToFix = new ArrayList<>();
  private String pathToScan;
  private String outputPath;

  public static void main(String[] args) {
    if (args.length == 1) {
      InspireBPMNFixer fixer = new InspireBPMNFixer();

      fixer.validateQNamePrefix();
      fixer.compileFiles(args);
      fixer.fixFiles();
    } else {
      logger.info("Invalid argument count. Usage: \"InspireBPMNFixer.jar [PATH]\" "
        + "where PATH can be a directory of .bpmn files or a single .bpmn file.");
    }
  }

  private void validateQNamePrefix() {
    if (!QNAME_PREFIX_CHAR.matches("[A-Z]|[a-z]|_|:")) {
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
      Document document = parseFileAndGetDocument(file);
      NodeList elements = document.getElementsByTagName("*");

      for (int i = 0; i < elements.getLength(); ++i) {
        Node element = elements.item(i);

        if (element.getNodeType() == Node.ELEMENT_NODE) {
          String tag = element.getNodeName();

          if (tag.equalsIgnoreCase("bpmn2:conditionexpression")) {
            convertExpressionTag(element, "inspire:Rule");
          } else if (tag.equalsIgnoreCase("bpmn2:timeduration")) {
            convertExpressionTag(element, "inspire:StringExpression");
          } else {

            // For now we are only fixing qualified names in the text content of tags without any
            // additional attributes. This might be subject to change if there are any unhandled
            // BPMN edge cases.
            if (element.getAttributes().getLength() == 0 && element.hasChildNodes()) {
              fixDigitPrefixedQNameForTag(element);
            } else {
              fixDigitPrefixedQNamesForTagAttributes(element);
            }
          }
        }
      }

      writeFixedFile(file, document);
    }

    logger.info(
      "Fixed file" + ((filesToFix.size() > 1) ? "s have" : " has") + " been written to: \""
        + pathToScan + "\\" + OUTPUT_FOLDER_NAME + "\"");
  }

  private Document parseFileAndGetDocument(File file) {
    Document document = null;
    try {
      logger.info("Parsing \"" + file.getName() + "\"");
      document = buildDocument(file);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      logger.error("Error while parsing " + file.getName());
      System.exit(1);
    }

    return document;
  }

  private Document buildDocument(File file)
    throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document document = builder.parse(file);

    document.getDocumentElement().normalize();
    return document;
  }

  /**
   * Converts a BPMN expression tag from BPM Inspire to Camunda Platform 7.
   *
   * <p>Example for a BPM Inspire expression tag:
   * {@code <bpmn2:conditionExpression xsi:type="inspire:Rule" id="ID" expression="EXPRESSION"/>}
   * <br />
   * Converted to Camunda Platform 7's format:
   * {@code <bpmn2:conditionExpression
   * xsi:type="bpmn:tFormalExpression">EXPRESSION</bpmn2:conditionExpression>}</p>
   *
   * @param tagElement     The XML node representing the BPMN expression tag
   * @param inspireXsiType The BPM Inspire xsi:type to convert
   */
  private void convertExpressionTag(Node tagElement, String inspireXsiType) {
    NamedNodeMap attributes = tagElement.getAttributes();

    Node expressionAttr = attributes.getNamedItem("expression");
    Node idAttr = attributes.getNamedItem("id");
    Node xsiTypeAttr = attributes.getNamedItem("xsi:type");

    if (expressionAttr != null && idAttr != null && xsiTypeAttr != null) {
      if (xsiTypeAttr.getTextContent().equalsIgnoreCase(inspireXsiType)) {
        attributes.removeNamedItem("expression");
        attributes.removeNamedItem("id");
        xsiTypeAttr.setNodeValue("bpmn:tFormalExpression");

        String expression = expressionAttr.getTextContent();
        tagElement.setTextContent(expression);
      }
    }
  }

  /**
   * Checks and fixes a tag's qualified name starting with a digit.
   *
   * <p>Example for an invalid qualified name: {@code <bpmn2:incoming>0123456789</bpmn2:incoming>}
   * </p>
   *
   * <p>The found qualified name will be prefixed with the prefix specified in
   * <em>InspireBPMNFixer.QNAME_PREFIX</em>. All qualified names in a file receive the same prefix
   * to keep them consistent.</p>
   *
   * @param tagElement The XML node representing the tag which contains a potentially invalid
   *                   qualified name
   */
  private void fixDigitPrefixedQNameForTag(Node tagElement) {
    String textContent = tagElement.getFirstChild().getTextContent()
      .trim().replace("\\n", "").replace("\\r\\n", "");

    // Tag text content starts with a digit
    if (!textContent.isEmpty() && textContent.matches("^[0-9].*$")) {
      tagElement.setTextContent(QNAME_PREFIX_CHAR + textContent);
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
   * @param tagElement The XML node representing the tag whose attributes contain potentially
   *                   invalid qualified names
   */
  private void fixDigitPrefixedQNamesForTagAttributes(Node tagElement) {
    NamedNodeMap attributes = tagElement.getAttributes();

    for (int i = 0; i < attributes.getLength(); ++i) {
      Node attribute = attributes.item(i);

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

  private void writeFixedFile(File file, Document document) {
    logger.info("Writing fixed file to \"" + OUTPUT_FOLDER_NAME + "\\" + file.getName() + "\"");

    try {
      var outputFolder = new File(outputPath);
      if (!outputFolder.exists()) {
        if (!outputFolder.mkdir()) {
          logger.error("Error while creating output folder: " + outputPath);
          System.exit(1);
        }
      }

      FileOutputStream out = new FileOutputStream(outputPath + file.getName());
      transformDocument(document, new BufferedOutputStream(out));
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