# BPMN File Fixer for BPM Inspire Diagrams

This command-line application allows you to fix and (partially) convert BPMN files that were created with BPM Inspire, so you can open them with Camunda Modeler.

## Use case

Diagrams created with certain older versions of BPM Inspire in some cases might contain qualified names (QNames) like IDs which don't adhere to the XML specification. An example of an invalid qualified name would be: `0123456789`

This tool scans the supplied BPMN files for such QNames and corrects them by prepending a valid prefix (default: `_`). As the prefix is the same throughout the whole file, references to qualified names are maintained.

## Features

🔧 Corrects invalid, digit-prefixed QNames in BPM Inspire-generated BPMN files (preventing errors ⛔) \
🔧 Converts specific expressions from the BPM Inspire format to the Camunda Platform 7 format (preventing warnings ⚠)

## Limitations

This tool was tested only with a select amount of diagrams generated with an unknown, old version of BPM Inspire. Camunda Modeler tries to follow the BPMN & XML specifications as close as possible, thus most likely there are more edge cases that would need fixing/conversion to ensure that every diagram can be opened. If you encounter such a case, please open an issue.

| ⚠ | This application is not intended to be a fully-fledged migration solution. |
|--|--|

After using this tool, you will still need to convert a lot of elements such as *Script Tasks*, *Service Tasks*, *User Tasks*, *Expressions* as well as BPM Inspire specific BPMN extensions to the Camunda Platform.

To ensure that you are eventually working with a clean BPMN state, it is recommended to use Camunda Modeler to copy and paste the diagram outputted by this tool to a fresh BPMN file. This will strip all the BPM Inspire extensions. Make sure to create a backup of the original file beforehand. Afterwards the actual migration work can be performed.

## Building

Build the tool with the following commands to create an executable JAR:

```bash
  git clone https://github.com/hkupitz/inspire-bpmn-fixer.git
  cd inspire-bpmn-fixer
  mvn package
```
## How to use

You can run the tool directly from the command-line:

```bash
java -jar InspireBPMNFixer.jar [PATH]
```

`[PATH]` can either be a directory that contains BPMN files or a single BPMN file itself.
## FAQ

#### Where are the fixed `.bpmn` files stored?

By default, they are written to `[PATH]\fixed`, keeping the original, untouched diagrams in the parent folder.

#### Which BPMN expression tags are automatically converted to the Camunda Platform 7 format?

`<bpmn2:conditionExpression />`\
`<bpmn2:timeDuration />`