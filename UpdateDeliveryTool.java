/*
    Copyright (c) 1996-2014 Ariba, Inc.
    All rights reserved. Patents pending.

    $Id$

    Responsible: achandra
*/

package ariba.tools.update;

import ariba.tool.harness.tasks.HarnessTask;
import ariba.util.core.ListUtil;
import ariba.util.core.MapUtil;
import ariba.tool.util.CommonKeys;
import ariba.tool.util.Properties;
import ariba.tool.util.ToolsUtil;
import ariba.util.core.FastStringBuffer;
import ariba.util.core.Fmt;
import java.util.Map;
import ariba.util.core.IOUtil;
import ariba.util.core.ServerUtil;
import ariba.util.core.StringUtil;
import ariba.util.core.SystemUtil;
import ariba.util.core.URLUtil;
import java.util.List;
import ariba.util.formatter.IntegerFormatter;
import ariba.util.io.CSVReader;
import ariba.util.io.CSVWriter;
import ariba.util.xml.XMLParseException;
import ariba.util.xml.XMLUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
//StattusFailed=3
//StatusSuccess=2

public class UpdateDeliveryTool extends HarnessTask
{
        //The product name of the instance that is getting updated
    String product = null;
        //The version in the depot that needs to be used as the base image
    String depotVersion = null;
        //The child directory under <depot>/<product>/<version>/<child> that containa the product installation.
    String depotImageDir = null;
        //if true indicates coreserver is being processed else web components
    private boolean nowProcessing;
        //conatins the path of either coreserver or web components
    private String path =null;
        // A copy of the original path
    private String path_save = null;
        // Windows virtual drives used by UDT (if any)
    private String[] drive = {null, null, null};

    /*
        template to construct the location for the UM files for
        other updates  in an instance
    */
    private static String updateLocation = null;
    private static int dependencyErrorCount =0;
    MessageDigest md = null;

    String msg = null;
    Date d = null;

    String logs = StringUtil.strcat("logs",Constants.fs,"HarnessLog.txt");
    String reports =
            StringUtil.strcat("logs",Constants.fs,"reports",Constants.fs,"UDT.htm");

    /**
     * This indicates if the UDT is running in silent mode or not.
     * By default its false
     */
    boolean silentMode = false;
    /**
     * In case of an update being reverted this Hastable contains a mapping between
     * component name and the version number that needs to be made active. This info is fetched from
     * the componentversions.csv file. This file is created before the update gets installed
     * and contains entries of the form
     * componentname,version
     * Where version is the version of the component before this updated gets installed.
     * So when a update gets reverted all the components get restored to the version specified in this file
     * if the component is currently active when the update gets reverted.
     */
    Map revertVersions = MapUtil.map();
    /**
     * A boolean to indicate if the componentversions.csv file should be read.
     * IF true then the file will be read. Will be set to true in case of revert's
     */
    boolean readComponentVersions = false;
    /*
        Contains list of Components that have to be excluded from
        all operations depending on the OS
    */
    Map excludeBOM = MapUtil.map();
    /*
        Contains list of files that have to be excluded from
        all operations depending on the OS
    */
    Map excludeFile = MapUtil.map();
    /**
     * Used only in case of Buyer.
     * Contains a list of feature specific directories that need to be excluded
     * if the feature is not installed.
     */
    Map excludeFeatureFile = MapUtil.map();
    /*
        Contains list of files that have to be excluded from
        validation depending on the OS
        The BuildInfo.csv files go into this.
    */
    Map ignoreFile = MapUtil.map();
    /**
        Initializes the list of BOM's and files that need
        to be excluded when doing valiation and restoration
    */
        //A boolean that indicates if the user did not
        //run the update when prompted to do so.
    boolean hasNotRun = false;
    String osName = null;

        // Location of the components folder in the Depot
    String ComponentInDepotDir = Constants.ComponentInDepot;
        // Location of the base image in the Depot
    String BaseImageDir = Constants.BaseImage;

    /**
     * This method initializes the feature file directories that can be ignored while printing
     * log messages. Basically the installer copies the BOM file over for optional feature components
     * even thought the customer does not choose the feature. So when the UDT trie's to upgrade such a component
     * it tries to uninstall/backup the existing version. But since the installer did not copy the files over the UDT thinks the
     * files are missing and print's a
     * File a/b/c/.txt does not exist and will not be backed up as part of uninstallation.
     * In order to avoid printing misleading messages the UDT will now keep track of feature specific files.
     * When it finds a file missing it will check if its a feature specific file. It will then check to see
     * if the user had installed the feature and then deleted the files by accident (checks install.sp to find otu if the
     * feature had been installed or not). If true then the message will get printed. If the user had never installed the feature
     * then no message will get printed.
     * @return
     */
    private boolean initalizeFeatureFileList ()
    {
        List vFeatureFile=null;
        File excludeFeatureFileName =
            new File(Fmt.S(Constants.TaskDirContent,Constants.ExcludeFeatureFile));
            //Read contents of the file
        try {
            Log.update.debug("Reading %s file", Constants.ExcludeFeatureFile);
            vFeatureFile = CSVReader.readAllLines(excludeFeatureFileName,
                                          SystemUtil.getFileEncoding());

        }
        catch (IOException ioe) {
                //These files have to exist for the instance. Return false
                //if there is an exception.
            Log.update.debug("Error while reading file %s :",
                             ioe.getMessage());
            Log.update.warning(6799,excludeFeatureFileName);
            return false;
        }
            //this method does some OS Specific stuff when there are more than 2 comma separated
            //entries in a line (but its safe to use here since we will have only two entries per line).
        createExcludeList(vFeatureFile,excludeFeatureFile);

            //Now read the install.sp file
        try {
            Properties p =
                ToolsUtil.getPropertiesFromFile(Fmt.S("%s/etc/install/install.sp",path));
            cleanFetaureFileList(p,Fmt.S(CommonKeys.ProductTESelected,product));
            cleanFetaureFileList(p,Fmt.S(CommonKeys.ProductContractsSelected,product));
            cleanFetaureFileList(p,Fmt.S(CommonKeys.ProductEformsSelected,product));
            cleanFetaureFileList(p,Fmt.S(CommonKeys.ProductInvoiceSelected,product));
        }
        catch (IOException ioe) {
                //since install.sp could not be read we assume none of the features are
                //installed for the sake of logging
            Log.update.debug(Fmt.S("Could not read install.sp : %s",ioe.getMessage()));
        }

        return true;

    }

    /**
     * This mehtod checks to see if the key has a value of true. This implies that the
     * optional feature is installed. This would mean that it should not be excluded
     * (when it comes to prining the log message) and hence will
     * be removed from the excludeFeatureFile list. Here are the key mappings from
     * install.sp to ExcludeFeatureFile.csv
     * buyer.contracts.selected => MA
     * buyer.eforms.selected => EFORM
     * buyer.invoice.selected  => INVOICE
     * buyer.te.selected => TE
     * @param p A Properties hash of the install.sp
     * @param key A property in the install.sp
     */
    private void cleanFetaureFileList (Properties p, String key)
    {
        if (p==null) {
            return;
        }
        String value = (String)p.get(key);
        if (StringUtil.nullOrEmptyOrBlankString(value) ||
                !value.equals("true")) {
                //assume feature is not installed.
            return;
        }
        String featureValue = null;
        if (key.equals(Fmt.S(CommonKeys.ProductContractsSelected,product))) {
            featureValue = Constants.MA;
        }
        else if (key.equals(Fmt.S(CommonKeys.ProductEformsSelected,product))) {
            featureValue = Constants.EForm;
        }
        else if (key.equals(Fmt.S(CommonKeys.ProductInvoiceSelected,product))) {
            featureValue = Constants.Invoicing;
        }
        else {
            featureValue = Constants.TE;
        }
        Iterator e = excludeFeatureFile.keySet().iterator();
            //a list of keys that need to be deleted. Cannot delete while enumerating so creating a
            //vector. I will then go thru the vector and delete the keys
        List v = ListUtil.list();
        String tempKey = null, tempValue = null;
        while (e.hasNext()) {
            tempKey = (String)e.next();
            tempValue = (String)excludeFeatureFile.get(tempKey);
            if (featureValue.equals(tempValue)) {
                v.add(tempKey);
            }
        }
            //now delete the keys from the excludeFeatureFileList
        for (int i =0; i<v.size();++i) {
            tempKey = (String)v.get(i);
            excludeFeatureFile.remove(tempKey);
        }
        return;
    }


    private boolean initializeFileList ()
    {
        List vBOM = null, vFile=null, vIgFile=null;
        File excludeBOMFile =
            new File(Fmt.S(Constants.TaskDirContent, Constants.ExcludeBOM));
        File excludeFileFile =
            new File(Fmt.S(Constants.TaskDirContent, Constants.ExcludeFile));
            //Read contents of the file
        try {
            Log.update.debug("Reading %s file", Constants.ExcludeBOM);
            vBOM = CSVReader.readAllLines(excludeBOMFile,
                                          SystemUtil.getFileEncoding());
            vFile = CSVReader.readAllLines(excludeFileFile,
                                           SystemUtil.getFileEncoding());
        }
        catch (IOException ioe) {
                //These files have to exist for the instance. Return false
                //if there is an exception.
            Log.update.debug("initializeFileList: Error while reading file %s :",
                             ioe.getMessage());
            return false;
        }

        createExcludeList(vBOM,excludeBOM);
        createExcludeList(vFile,excludeFile);

        File ignoreFileFile =
            new File(Fmt.S(Constants.TaskDirContent,Constants.IgnoreFile));
            //This file may or may not exist for instance
        if (ignoreFileFile.exists()) {
            try {
                vIgFile=CSVReader.readAllLines(ignoreFileFile,
                                               SystemUtil.getFileEncoding());
            }
            catch (IOException ioe) {
                Log.update.debug(
                    "initializeFileList: Error while reading file %s :",
                    ioe.getMessage());
                return false;
            }
            createExcludeList(vIgFile,ignoreFile);
        }

            //Initialize excludeFile for BuildInfo.csv depending
            //on CoreServer or WebComponents
        if (nowProcessing) {
            ignoreFile.put(Constants.CSBuildInfo,Constants.All);
        }
        else {
            updateExcludeFileForAllLocales ();
        }
        return true;

    }


    /**
        this function updates the exclude list.
        v contains the list to be excluded (read from a file)
        and h contains a mapping between the file/component name
        and the os
    */
    private void createExcludeList (List v, Map h)
    {
        for (int i=0;i<v.size();++i) {
            List line = (List)v.get(i);
                //now the first element is assumed to be the component name.
            if (line.size()==2) {
                h.put(
                    ListUtil.firstElement(line),
                    line.get(1));
            }
                //aplicable to more than one OS
            /*
                Consider an entry of the form
                fileName,HP-UX,AIX
                In this case if the current OS is AIX we should not ignore the file.
                There are two ways of doing this.
                1) Don't put the entry int he exclude list (that's what i am doing)
                2) Put an entry in the exclude list with the OS as AIX. This would
                mean that this file would be ignored for all OS'es except AIX.

                If the current SO is SunOS this file needs to be ignored. In case
                put an entry in the exclude list with any OS other than SunOS.
            */
            else if (line.size() > 2) {
                if (!checkIfApplicableToCurrentPlatform(line)) {
                    h.put(
                        ListUtil.firstElement(line),
                        line.get(1));
                }
            }
        }
        return;
    }
    /**
        Given a fileName or a componentName and a list of
        applicable platforms then this method checks if the current
        platform is in the list of applicable platforms. If yes
        it return a true. The first element is assumed to
        be the fileName or componentName
    */
    private boolean checkIfApplicableToCurrentPlatform (List v)
    {
        for (int i=1;i<v.size();++i) {
            String temp= (String)v.get(i);
            if (osName.equals(temp)) {
                return true;
            }
        }
        return false;
    }

    /**
        Updates the excludeFile hash with the list of BuildInfo.csv's
        in the different locales.
    */
    private void updateExcludeFileForAllLocales ()
    {
        File f = new File(Fmt.S(Constants.ResourceDir,path));
        String[] list = f.list();
        for (int i=0;i<list.length;++i) {
            String fileName =
                Fmt.S(Constants.InstanceBuildInfoLocation,path,list[i]);
            File tempFile = new File(fileName);
            if (!tempFile.exists()) {
                    //No BuildInfo.csv to be ignored.
                continue;
            }
            ignoreFile.put(Fmt.S(Constants.WCBuildInfo,list[i]),
                           Constants.All);
        }
        return;
    }


    public int run ()
    {

        if (ServerUtil.isWin32) {
            osName = Constants.Win;
        }
        else {
            osName = SystemUtil.getOperatingSystem();
        }

        silentMode = Boolean.getBoolean(Constants.Silent);
        depotVersion = System.getProperty(Constants.Version);
        product = System.getProperty(Constants.Product);

        if (StringUtil.nullOrEmptyOrBlankString(depotVersion)) {
	        Log.update.warning(6926,depotVersion);
	        return StatusFailed;
        }

        if (StringUtil.nullOrEmptyOrBlankString(product)) {
	        Log.update.warning(6927,product);
	        return StatusFailed;
        }
        Log.update.debug(Fmt.S("Product name of depot image : %s",product));
        Log.update.debug(Fmt.S("Version number of depot image : %s", depotVersion));

        /*
            Unfortunately the depot image structure is different for Buyer and ACM/Analysis.
            Buyer gets installed in
                <version>/image
            where as ACM and Analysis get installed in
                <version>/Server
            So depending on the product i set the appropriate directory
        */
        if (CommonKeys.buyer.equalsIgnoreCase(product)) {
            depotImageDir = "Server";
        }
        else if (CommonKeys.acm.equalsIgnoreCase(product) ||
                CommonKeys.sourcing.equalsIgnoreCase(product) ||
                CommonKeys.analysis.equalsIgnoreCase(product)) {
            depotImageDir = "Server";
        }
        else {
            //unrecognized product
            Log.update.warning(6927,product);
            return StatusFailed;
        }

            //Get the current working directory
        String cwd = SystemUtil.getCwd();
        int dot = cwd.lastIndexOf(".");
        cwd = cwd.substring(0,dot);
        cwd = StringUtil.strcat(cwd,"%s");


        int errorCount=0;
            //chehck if the manifest file nanme is definend
        String umFileName = System.getProperty(Constants.Manifest_File);
        if (umFileName == null) {
            Log.update.error(6777,Constants.Manifest_File);
            return StatusFailed;
        }
            //Strip out ".xml" if any
        umFileName = Util.getManifestFileName(umFileName);
            //construct the path to the manifest file in the depot and read it
        String UMFileLocation = Fmt.S(Constants.partialUmFilePath,umFileName);
        Element rootElement = Util.readXMLFile(UMFileLocation,true);
        if (rootElement==null) {
            Log.update.debug(
                "Could not retrieve root element for document %s",
                UMFileLocation);
            Log.update.error(6758,UMFileLocation);
            return StatusFailed;
        }

        Element updateElement = Util.getUpdateElementInUM(rootElement);
        if (updateElement==null) {
            Log.update.debug(
                "Element %s not defined in file %s",
                Constants.UpdateTag,
                UMFileLocation);
            Log.update.error(6758,UMFileLocation);
            return StatusFailed;
        }

        String updateName = updateElement.getAttribute(Constants.NameAttr);
        if (StringUtil.nullOrEmptyOrBlankString(updateName)) {
            Log.update.warning(6768,updateName,
                               Constants.NameAttr,
                               Constants.UpdateTag,
                               UMFileLocation);

            return StatusFailed;
        }

            //check if the umFileName matches the update name.
            //if the file name is revert_<updatename> then don't worry
        String tempFileName = StringUtil.strcat("revert_",updateName);
        if (!umFileName.equalsIgnoreCase(tempFileName)) {
            if (!umFileName.equals(updateName)) {
                Log.update.error(6920,umFileName,updateName);
                return StatusFailed;
            }
        }
        else {
                //this is a revert. Set a boolean to indicate that the componentversions.csv should
                //be read
            Log.update.debug("This is a revert.");
            readComponentVersions = true;
        }
        /*
            For now lets just do update of one instance.
            Prompt the user for the server and Web Components directory
            if not specified in the System Property
        */

        String server  = System.getProperty(Constants.Server_Path);
        String webcomp = System.getProperty(Constants.WebComponent_Path);

        if (server != null) {
            path = server;
            path_save = server;
        }
        else if (webcomp != null) {
            path = webcomp;
            path_save = webcomp;
        }

        if (ServerUtil.isWin32 &&
            (CommonKeys.analysis.equalsIgnoreCase(product) ||
            CommonKeys.acm.equalsIgnoreCase(product))) {
                // Change Depot root and product instance root to be virtual
                // drives to avoid protential problem with very long
                // filepaths used by Analysis and ACM.
            setVirtualDrives();
        }

        /*
            if a user has specified both core server and webcomponents then do
            one at a time. i will do core server first
        */

        if (server != null) {
            msg = Fmt.S("Processing CoreServer: %s",server);
            print(msg);
            console(msg);
            print(Fmt.S("Processing : %s",server));
            nowProcessing = true;
            _context.write(Constants.InstanceLocation,server);


            if (!processUpdate(updateElement,umFileName)) {
                if (hasNotRun) {
                    finalizeUDT();
                    return StatusHasNotRun;
                }
                Log.update.error(6773,Fmt.S(cwd,logs));
                ++errorCount;
            }
            else {
                msg = Fmt.S("Done Processing CoreServer: %s",server);
                console(msg);
                print(msg);
                displayUpdates();
            }
        }
        else if (webcomp != null) {
            msg = Fmt.S("Processing WebComponents: %s",webcomp);
            console(msg);
            print(msg);
            print(Fmt.S("Processing : %s",webcomp));
            nowProcessing = false;
            _context.write(Constants.InstanceLocation,webcomp);

            if (!processUpdate(updateElement,umFileName)) {
                if (hasNotRun) {
                    finalizeUDT();
                    return StatusHasNotRun;
                }
                Log.update.error(6773,Fmt.S(cwd,logs));
                ++errorCount;
            }
            else {
                msg = Fmt.S("Done Processing WebComponents: %s", webcomp);
                console(msg);
                print(msg);
                displayUpdates();
            }
        }
        else {
                //Nothing to update. This will never happen since the launch
                //scripts wont allow the TH to be launched until either the
                //CoreServer or WebComponents directory has been specified.
                //But just in case.
            Log.update.warning(6841,
                               "No CoreServer or WebComponent directory to update.");
            finalizeUDT();
            return StatusFailed;
        }


        if (errorCount!=0) {
            finalizeUDT();
            return StatusFailed;
        }

        console(Fmt.S(
            "A report has been generated. It can be found at %s",
            Fmt.S(cwd,reports)));


        /*
            Not reading from vpd.preoperties for the time being
            Use below code to read from vpd.properties.

            if (!update(Constants.ServerUID,updateElement)) {
            return StatusFailed;
            }

            if (!update(Constants.WebComponentUID)) {
            return StatusFailed;
            }
        */
        finalizeUDT();
        return StatusSuccess;
    }

    /**
        Pads a string with spaces to bring it up to size
        equal to length
    */
    private String fillupStringWithSpaces (String value,
                                           int    length)
    {
        int spacesToPad = length-value.length();
        FastStringBuffer buffer = new FastStringBuffer(value);
        for (int i=0;i<spacesToPad;++i) {
            buffer.append(" ");
        }
        return buffer.toString();
    }

    /**
        Displays the updates in order of build number that are
        currently installed on the system
    */
    private void displayUpdates ()
    {

        List v = Util.getListOfUpdateAndBuildInfo(path);
        if (v==null) {
            Log.update.warning(6841,"An error occurred while retrieving the list of updates.");
            return;
        }
        if (v.size()==0) {
            msg = "No updates are currently applied.";
            print(msg);
            console(msg);
            return;
        }
            //Get the size of the longest update name and time applied. This
            //is to format the strings to that the columns are aligned
            //when displayed
        int maxLengthUpdateName = Constants.DisplayUpdate.length();
        int maxLengthTimeApplied = Constants.DisplayTime.length();
        for (int i=0;i<v.size();++i) {
            UpdateAndBuildInfo u = (UpdateAndBuildInfo)v.get(i);
            String updateName = u.getUpdateName();
            String timeApplied = u.getTimeApplied();
            if (maxLengthUpdateName<updateName.length()) {
                maxLengthUpdateName = updateName.length();
            }
            if (maxLengthTimeApplied<timeApplied.length()) {
                maxLengthTimeApplied = timeApplied.length();
            }

        }
            //Give a little more buffer
        maxLengthUpdateName+=5;
        maxLengthTimeApplied+=5;
        console("Given below is the list of updates applied on the system.");
        console(
            fillupStringWithSpaces(Constants.DisplayUpdate,maxLengthUpdateName)+
            fillupStringWithSpaces(Constants.DisplayTime,maxLengthTimeApplied)+
            Constants.DisplayBuild);
        for (int i=0;i<v.size();++i) {
            UpdateAndBuildInfo u = (UpdateAndBuildInfo)v.get(i);
            console(
                fillupStringWithSpaces(u.getUpdateName(),maxLengthUpdateName)+
                fillupStringWithSpaces(u.getTimeApplied(),maxLengthTimeApplied)+
                u.getBuildNumber());
        }
        return;
    }


    /**
        Called for each installed location (Server and WebComponents)
        path = path of either coreserver or webcomponents.
        umFileName is the name of the manifest file for this update
    */
    private boolean processUpdate (Element updateElement, String umFileName)
    {
            //The manifest file for theh current update in the depot
        String UMFileLocation = Fmt.S(Constants.partialUmFilePath,umFileName);
            //indicates if a new component is coming in with this update
        boolean newComponent = false;
            //indicates if a component is being uninstalled completely
        boolean removeComponent = false;
            //name of the update beeing processed
        String currentUpdateName = null;
            //conatins the names of the updates that are being restored when a update
            //gets uninstalled.
            //contains the names of the components that are being restored when a update
            //List restoredUpdates = ListUtil.list();
            //gets uninstalled and which need an entry in product.csv
        List restoredComponents = ListUtil.list();
        int i=0;
        dependencyErrorCount = 0;

        /**
            Get the name of the update from the file
        */
        currentUpdateName = updateElement.getAttribute(Constants.NameAttr);
        if (StringUtil.nullOrEmptyOrBlankString(currentUpdateName)) {
            Log.update.warning(6768,currentUpdateName,
                               Constants.NameAttr,
                               Constants.UpdateTag,
                               UMFileLocation);

            return false;
        }


        print(Fmt.S("The update being applied is %s",umFileName));
        /*
            construct a template for the location of other
            manifest files in an instance
        */

        updateLocation = StringUtil.strcat(path,Constants.UpdateInInstance,
                                           "%s.xml");

            //Check if this update is already installed
            //Don't do the check if its a revert file
        String tempFileName = StringUtil.strcat("revert_",currentUpdateName);
        if (!umFileName.equalsIgnoreCase(tempFileName)) {
            int updateStatusResult =
                isUpdateAlreadyInstalled(Fmt.S(updateLocation,currentUpdateName,umFileName));
            if (updateStatusResult == 0) {
                    //Error in processing the file
                Log.update.warning(6840,
                                   Fmt.S(updateLocation,currentUpdateName,umFileName));
                return false;
            }
            else if (updateStatusResult == 1) {
                    //Update is already done
                Log.update.warning(6810,umFileName,path_save);
                return true;
            }
        }

        displayUpdates();
        hasNotRun = false;
        if (!silentMode) {
            if (!Util.readUserInputUntilEquals(
               "Do you wish to continue and apply the update "+umFileName+" (y/n): ","y","n")) {
                hasNotRun = true;
                return false;
            }
        }

            //read the contents of the files that contains BOM's and files
            //to be excluded
        if (!initializeFileList()) {
            Log.update.warning(6923);
            return false;
        }
            //if buyer and CoreServer then initialize the featurefile list
        if (CommonKeys.buyer.equalsIgnoreCase(product) && nowProcessing) {
            if (!initalizeFeatureFileList()) {
                return false;
            }

        }
        /*
            This creates the backupdirectory
            etc/backup/updates/<current_update>
        */
        if (!createBackUpDirectory(Fmt.S(Constants.UpdateBackUp,
                                         path,currentUpdateName))) {
            return false;
        }

        /*
            This creates the update directory in the instance
            etc/updates/<update>
        */
        if (!createBackUpDirectory(StringUtil.strcat(path,
                                                     Fmt.S(Constants.UpdateInInstance,currentUpdateName)))) {
            return false;
        }

            //For fresh update's I used to the below at a later stage.
            //I now do it right upfront. The status of the manifes gets
            //changed only after update goes thru.

            /////////////////////////////////////////////////////////////////////////
            //Also backup the manifest dtd to the same directory. Remove this
            //code once the update installer starts to change the DOCTYPE
            //decleration in the manifest file to make it refer to the right dtd
            //location.//////////////////////////////////////////////////////////////
        if (!backupManifestDTD(StringUtil.strcat(path,
                                                 Fmt.S(Constants.UpdateInInstance,currentUpdateName)))) {
            return false;
        }

            //copy the manifest file
        String umInInstance =
            Fmt.S(updateLocation,currentUpdateName,umFileName);
        if (!Util.makeFileWriteAble(umInInstance)) {
            Log.update.warning(6841,
                               Fmt.S("Could not make file %s writable",umInInstance));
            return false;
        }
        if (!IOUtil.copyFile(new File(UMFileLocation), new File(umInInstance))) {
            Log.update.warning(6806,UMFileLocation,umInInstance);
            return false;
        }

            //copy BuildInfo.csv
        String targetBuildInfo = StringUtil.strcat(path,Constants.UpdatesDir,
                                                   currentUpdateName,Constants.fs,Constants.BuildInfo);
        String sourceBuildInfo =
            StringUtil.strcat("tasks",Constants.fs,Constants.BuildInfo);
        if (!IOUtil.copyFile(new File(sourceBuildInfo), new File(targetBuildInfo))) {
            Log.update.warning(6806,sourceBuildInfo,targetBuildInfo);
            return false;
        }

        if (!readComponentVersionsCSV(currentUpdateName)) {
            return false;
        }

        /*
            Contains a mapping between the component name and the
            component version that are being installed with this update
            Used for quick check of dependencies.
        */
        Map updateHash = MapUtil.map();
        /*
            This is used for the actual update itself.
            Contains a mapping between the component name and the
            ariba.tools.update.ComponentElement

        */
        Map realUpdateHash = MapUtil.map();

        /*
            Contains a mapping between the component
            to be uninstalled and its version.
            Used for quick check of dependencies.
        */

        Map uninstallHash = MapUtil.map();
        /*
            This is used for the actuall uninstall itself.
            Contains a mapping between the componentName/updateName to
            a ariba.tools.update.ComponentElement or ariba.tools.update.UpdateElement
        */
        Map uninstallUpdateHash = MapUtil.map();
        /*
            Contains a mapping between the component name and the
            component version which are required by other components
            in the system.
        */
        Map dependencyHash = MapUtil.map();

        ComponentElement tempCompElement= null;

            //Initialize the updateHash and realUpdateHash
        int updateErrorCount = 0;
        NodeList componentList =
            Util.getNodeList(updateElement,Constants.ComponentTag);
        Map currentActiveVersion = MapUtil.map();
        if (componentList != null) {
                //there are some component tags. process them
            String activeVersion = null;
            if (componentList.getLength()>0) {
                print("Components that are coming in with this update are:");
            }
            else {
                print("Components that are coming in with this update are: None");
            }
            for (i=0;i< componentList.getLength();++i) {
                Element component = (Element)componentList.item(i);
                activeVersion = null;
                /*
                    Note : This assumes you cant ship two different versions of the same component
                    in the same update. In case you do this then the one read last will take precedence.
                */
                String componentName = component.getAttribute(Constants.NameAttr);
                    //Check if this components needs to be installed or not.
                if (isComponentNotValidForOS(componentName)) {
                    continue;
                }
                String version = component.getAttribute(Constants.VersionAttr);
                print(Fmt.S("This update will upgrade component %s %s.",
                            componentName,
                            version));

                String compInDepot =
                    Fmt.S(Constants.BomInDepot,componentName,version,componentName);
                tempCompElement = getComponentInBOM(
                    compInDepot,
                    componentName,
                    version,false);
                if ((tempCompElement==null) ||
                    (Constants.CompNotFnd.equals(tempCompElement.getStatus()))) {
                    Log.update.warning(6809,componentName,version,compInDepot);
                    ++updateErrorCount;
                    continue;
                }
                List v = tempCompElement.getDependencies();
                /*
                    This is the list of dependencies for this component in the BOM.
                    There is no version number so we will use "*" ie.
                    any version is fine as long as it will be active"
                */
                for (int j=0;j<v.size();++j) {
                    String compName = (String)v.get(j);
                    String key = StringUtil.strcat(compName,"|","*");
                    dependencyHash.put(key,"*");
                }
                /*
                    Now initialize tempCompElement.nextActive(). This actually
                    will be the current active version of the component in the
                    instance if any.
                */

                String pathToBOMFileIninstance = StringUtil.strcat(path,
                                                                   Fmt.S(Constants.BOMLocation,componentName));
                if (!(new File(pathToBOMFileIninstance)).exists()) {
                    Log.update.debug("processUpdate: This %s is a new Component", componentName);
                    /*
                        This means we need to back up the product.csv
                        since it will be changed.
                    */
                    if (!backupProductCSV(currentUpdateName)) {
                        return false;
                    }
                        //this is a new component coming in with the update. So when revert takes place
                        //this component has to be completely removed.
                    currentActiveVersion.put(componentName,Constants.NoActive);
                    newComponent=true;
                }
                else {
                    activeVersion = getActiveVersion(pathToBOMFileIninstance,
                                                     componentName,
                                                     MapUtil.map());
                    if (activeVersion == null) {
                        Log.update.warning(6841,
                                           Fmt.S("Error in getting the active component in %s",
                                                 pathToBOMFileIninstance));
                        ++updateErrorCount;
                        continue;
                    }
                    if (activeVersion.equals(Constants.NoActive)) {
                        Log.update.debug(
                            Fmt.S("processUpdate: No version of %s is active",
                                  componentName));
                        tempCompElement.setCICStatus(Constants.NoActive);
                    }
                    else {
                        ComponentElement ce = getComponentInBOM(pathToBOMFileIninstance,
                                                                componentName,activeVersion,false);
                        if (ce==null) {
                            Log.update.warning(6809,componentName,activeVersion,
                                               pathToBOMFileIninstance);
                            ++updateErrorCount;
                            continue;
                        }
                        tempCompElement.setCICStatus(ce.getVersion());
                        tempCompElement.setNextActive(ce);
                    }
                }
                updateHash.put(componentName,version);
                realUpdateHash.put(componentName,tempCompElement);
            }
        }
        if (updateErrorCount != 0) {
            Log.update.warning(6774,Constants.ComponentTag,UMFileLocation);
            return false;
        }

            //save the componentversions.csv file if its not a revert
        if (!writeComponentVersionsCSV(currentActiveVersion,currentUpdateName)) {
            return false;
        }

            //Initaialize the uninstallHash and updateUninstallHash
        NodeList uninstallList =
            Util.getNodeList(updateElement,Constants.UninstallTag);
        int uninstallErrorCount =0;
        if (uninstallList!=null) {
            if (uninstallList.getLength()>0) {
                print(Fmt.S("Components and Updates that are being uninstalled are:"));
            }
            else {
                print(
                    Fmt.S("Components and Updates that are being uninstalled are: None"));
            }

            for (i=0;i<uninstallList.getLength();++i) {
                Element uninstall = (Element)uninstallList.item(i);
                String type = uninstall.getAttribute(Constants.TypeAttr);
                String uninstallName =
                    uninstall.getAttribute(Constants.NameAttr);
                String version = uninstall.getAttribute(Constants.VersionAttr);
                    //"type" attribute value can be either "update" or "coomponent"
                if (Constants.TypeAttrUpdate.equals(type)) {
                        //read the UM file for the update given in the name attribute
                    print(Fmt.S("Update being uninstalled is %s",uninstallName));

                    String pathToUmFile = Fmt.S(updateLocation,uninstallName,uninstallName);
                    if (!updateUninstallHash(uninstallHash,pathToUmFile,
                                             uninstallUpdateHash,uninstallName,dependencyHash,
                                             updateHash,realUpdateHash,currentUpdateName,
                                             restoredComponents)) {
                            //was not able to process the <uninstall type="update"> element
                        Log.update.warning(6917,pathToUmFile,uninstallName);
                        ++uninstallErrorCount;
                        continue;
                    }
                }
                else if (Constants.TypeAttrComponent.equals(type)) {
                    print(Fmt.S("Component being uninstalled %s %s",
                                uninstallName,
                                uninstall.getAttribute(Constants.VersionAttr)));

                    String compInInstance =
                        StringUtil.strcat(path,Fmt.S(Constants.BOMLocation,
                                                     uninstallName));

                    /**
                        Check if the version is a "*". This means the current
                        active version must be uninstalled and even if there
                        are superceded versions  they should not be made active.

                        First get the active version in BOM. If there is none
                        then this component has been uninstalled. So print out a
                        message and continue.
                    */
                    if ("*".equals(version)) {
                        String curActVer =
                            getActiveVersion(compInInstance,uninstallName,
                                             MapUtil.map());
                        if (curActVer == null) {
                            Log.update.warning(6772,uninstallName,"",compInInstance);
			    /** 
			     * IF any component is already deleted from the image, we don't consider that as a error.
			     * We just print warning and continue.
			     */
                            //++uninstallErrorCount;
                            continue;
                        }
                        if (curActVer.equals(Constants.NoActive)) {
                            Log.update.info(6792,
                                            uninstallName,"",compInInstance);
                            continue;
                        }
                        tempCompElement = getComponentInBOM(
                            compInInstance,
                            uninstallName,
                            curActVer,false);
                        if (tempCompElement == null ) {
                            Log.update.warning(6809,uninstallName,
                                               curActVer,compInInstance);
                            ++uninstallErrorCount;
                            continue;
                        }
                            //once this is uninstalled nothing will be active
                        tempCompElement.setNextActive(null);
                            //needs to be removed from product.csv
                        if (!backupProductCSV(currentUpdateName)) {
                            return false;
                        }
                        removeComponent = true;

                    }
                    else {
                        tempCompElement = getComponentInBOM(
                            compInInstance,
                            uninstallName,
                            version,true);
                        if (tempCompElement == null ) {
                            Log.update.warning(6809,uninstallName,version,
                                               compInInstance);
                            ++uninstallErrorCount;
                            continue;
                        }

                            //cannot uninstall a component which is not active
                        if (!tempCompElement.getStatus().equals(
                                Constants.StatusAttrActive)) {

                            Log.update.info(6792,
                                            tempCompElement.getComponentName(),
                                            tempCompElement.getVersion(),
                                            compInInstance);
                            continue;
                        }
                        if (tempCompElement.getNextActive()==null) {
                                //component needs to be removed from product.csv
                            if (!backupProductCSV(currentUpdateName)) {
                                return false;
                            }
                            removeComponent=true;
                        }
                    }
                    uninstallUpdateHash.put(uninstallName,tempCompElement);
                    uninstallHash.put(uninstallName,
                                      uninstall.getAttribute(Constants.VersionAttr));
                }
                else {
                        //wrong value for type. no need to stop. give warning and chug along
                    Log.update.warning(6768,type,Constants.TypeAttr,Constants.UninstallTag,
                                       uninstallName+".xml");
                    ++uninstallErrorCount;
                }
            }
        }
        if (uninstallErrorCount!=0) {
            Log.update.warning(6774,Constants.UninstallTag,UMFileLocation);
            return false;
        }
        /*
            Go thru the product.csv and check if the components listed are there
            in the system. Also if fullValidation is set to true then
            load the active component in each BOM and check if the file
            size and md5 of the file on disk matches with those given in the
            <file> tag.
        */
        Log.update.debug("processUpdate: Checking the products.csv");
        boolean fullValidation =
            Boolean.valueOf(System.getProperty(Constants.fullValidation)).booleanValue();
        msg = "Running product validation, this might take several minutes.";
        print(msg);
        console(msg);
        int checkProductResult = checkProduct(fullValidation);
        if (checkProductResult==0) {
            Log.update.warning(6834);
            return false;
        }
        else if (checkProductResult==1) {
                //some customizations detected and customer does not
                //want to continue
            hasNotRun = true;
            return false;
        }


        /*
            The active component in the BOMS in the instance also have
            dependencies although they specify no version number. So all
            they care is if the dependent component is active regardless
            of the version. So go thru all the BOM's and get the list of
            dependent components for each component whose status is active
        */
        Log.update.debug("processUpdate: Building the dependency from the BOM");
        if (!initializeBOMDependencies(dependencyHash,uninstallHash,updateHash)) {
            Log.update.warning(6831);
            return false;
        }
        /*
            Lets build the dependency hash. One thing to note is if an
            update is being uninstalled then there is no need to recurse
            through the dependencies of that update

            We need to go thru the Manifest file being used for the current update
            plus all the Manifest files on the instance with status "installed".
            This is because

            update 7 => depenedent on update 6
            current update is saying uninstall update 6

            The only way too catch this is go thru all the updates.
        */
        List listOfCurrentUpdates = getListOfUpdates();
        if (listOfCurrentUpdates==null) {
            Log.update.warning(6830);
            return false;
        }
        Log.update.debug(
            Fmt.S("processUpdate: Building the dependency hash for all the updates currently on the instance plus the current one."));

        for (int n=0;n<listOfCurrentUpdates.size();++n) {
            Element e = (Element)listOfCurrentUpdates.get(n);
            buildDependencyHash(e,dependencyHash,uninstallUpdateHash,false);
        }
            //initialize the hash for the current update
        buildDependencyHash(updateElement,dependencyHash,uninstallUpdateHash,false);
        if (dependencyErrorCount !=0) {
            Log.update.warning(6774,Constants.DependencyTag,UMFileLocation);
            return false;
        }
        /*
            At this point the dependencyHash contains components whose versions
            are required to be present in case this update needs to go through
        */
        msg = "Starting to check for dependencies.";
        print(msg);
        console(msg);
        if (!checkDependency(dependencyHash,updateHash,uninstallHash,
                             StringUtil.strcat(path,Constants.BOMLocation))) {
            Log.update.warning(6793,"dependency check",UMFileLocation);
            return false;
        }
        /*
            Now go thru the BOMS that came in with this update to check to see
            if any of them are of delivery type="diff". In that case the base version
            of this component must be the current active component
            these boms are located at updates/<update>/update/boms/*.bom
        */
        Log.update.debug("processUpdate: Staring to check for self dependency.");
        if (!checkSelfDependency(updateHash,uninstallHash)) {
            Log.update.warning(6793,"self dependency check",UMFileLocation);
            return false;
        }

        /*
            OK Ready to uninstall. Before uninstalling cheeck if you have the
            permissions to delete the file, copy the file to backup location
            Create the backup location if needed. Also check if the files for the
            component that becomes active after uninstallation are present in the back up.
        */
        msg = "Checking if OK to uninstall components/updates if any.";
        print(msg);
        console(msg);
        if (!checkIfOkToUninstall(uninstallUpdateHash)) {
            Log.update.warning(6774,Constants.UninstallTag,UMFileLocation);
            return false;
        }

        /*
            Ok ready to install. Before installing check if you have
            permissions to delete existing files, copy them to backup location.
            Create backup location if needed. Also check if the files for the component
            coming in exists in the depot.
        */
        msg = "Checking if OK to install components if any.";
        print(msg);
        console(msg);
        if (!checkIfOkToInstall(realUpdateHash)) {
            Log.update.warning(6820,currentUpdateName);
            return false;
        }


        /*
            This  method uninstalls on a component basis. If there are updates
            to be uninstalled it will make an attempt to uninstall all components
            in the update ebfore moving on to the next update/component
        */
        msg ="Starting to uninstall components if any.";
        print(msg);
        console(msg);
        if (!uninstall(uninstallUpdateHash,removeComponent,currentUpdateName)) {
            Log.update.warning(6774,Constants.UninstallTag,UMFileLocation);
            return false;
        }

        /*
            This method installs on a component basis. This includes copying the
            component files, updating the BOM files in the instance to reflect the latest
            status.
        */
        msg="Starting to install components if any.";
        print(msg);
        console(msg);
        if (!install(realUpdateHash,umFileName,currentUpdateName,restoredComponents)) {
                //First rollback whatever has been installed. Then rollback
                //all that's been uninstalled
            Log.update.warning(6841,
                               "Applying the update failed. "+
                               "Trying to revert back the update.");
            if (!installRollback(realUpdateHash,newComponent,currentUpdateName,
                                 restoredComponents)) {

                Log.update.warning(6841,"Could not do a successful rollback of installed components");
                return false;
            }
            if (!rbUninstallRestore(uninstallUpdateHash,removeComponent,currentUpdateName)) {
                Log.update.warning(6859);
                return false;
            }
            if (!rbUninstallBackup(uninstallUpdateHash,currentUpdateName)) {
                Log.update.warning(6858);
            }
            Log.update.warning(6841,"Successfully reverted back the failed update.");
            Log.update.warning(6758,UMFileLocation);
            return false;
        }
        print(Fmt.S("Done installing update for %s.",path_save));
        Util.updateBuildInfo(path, nowProcessing, depotVersion, depotImageDir);
        changeTaskStatus(currentUpdateName);
            //Change the permission of all the files. setX
        if (!Util.setX(path_save)) {
            Log.update.warning(6924);
        }

        return true;
    }

    /**
     * Depending on the type of update this method change's the status of its complementary task
     * i.e. if this is revert_sp1 then change the status of sp1 to "Has Not Run"
     * If it is sp1 then change the status of revert_sp1 to "Has Not Run"
     * @param updateName The name of the upadte
     */
    private void changeTaskStatus (String updateName)
    {
        String statusDir = null;
        if (readComponentVersions) {
                //change the status of the update used to install
            statusDir = Fmt.S(Constants.THStatusDir,path,updateName);
        }
        else {
                //change the status of the update used to uninstall
            statusDir = Fmt.S(Constants.THStatusDir,path,
                    StringUtil.strcat("revert_",updateName));
        }
        File f = new File(statusDir);
        if (!f.exists()) {
            Log.update.debug(Fmt.S("Status directory %s does not exist",statusDir));
        }
        else if (!IOUtil.deleteDirectory(f)) {
            Log.update.info(6929,updateName);
        }
        return;
    }

    /**
        Reads from componentversions.csv.
    */
    private boolean readComponentVersionsCSV (String currentUpdateName)
    {
            //no need to read the file since this is not a revert
        if (!readComponentVersions) {
            return true;
        }
        String fileName =
            StringUtil.strcat(Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
            Constants.ComponentVersions);

        List v = null;
        try {
            v = CSVReader.readAllLines(new File(fileName),
                                       SystemUtil.getFileEncoding());
        }
        catch (IOException ioe) {
            Log.update.debug(
                Fmt.S("Error while reading file %s : %s",
                      fileName, ioe.getMessage()));
            return false;
        }
        if (v==null) {
            return false;
        }
        int errorCount =0;
        for (int i=0;i<v.size();++i) {
            List line = (List)v.get(i);
                //if the number of items on the line is not equal to 2 then ignore line
            if (line.size()==0) {
                Log.update.debug(Fmt.S("Found empty line in %s",
                        Constants.ComponentVersions));
                continue;
            }
            else if (line.size()!=2) {
                Log.update.warning(6929,Constants.ComponentVersions,ListUtil.firstElement(line));
                ++errorCount;
                continue;
            }
            String compName = (String)ListUtil.firstElement(line);
            String version = (String) line.get(1);
            revertVersions.put(compName,version);
        }
        if (errorCount != 0) {
            return false;
        }
        Log.update.debug(Fmt.S("Finished reading %s.",Constants.ComponentVersions));
        return true;
    }
    /**
        Writes into componentversions.csv.
    */
    private boolean writeComponentVersionsCSV (Map h, String currentUpdateName)
    {
            //no need to write if this is a revert.
        if (readComponentVersions) {
            return true;
        }
            //read product.csv, go thru all components and get their active versions
        List v = readProductCSV();
        if (v==null) {
            return false;
        }
        String pathToBOMFileIninstance = null;
        for (int i=0;i<v.size();++i) {
            String compName = (String)v.get(i);
            pathToBOMFileIninstance= StringUtil.strcat(path,
                    Fmt.S(Constants.BOMLocation,compName));
            String activeVersion = getActiveVersion(pathToBOMFileIninstance,compName,MapUtil.map());
            if (activeVersion!=null) {
                h.put(compName,activeVersion);
            }
            else {
                return false;
            }
        }

        String fileName =
            StringUtil.strcat(Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
            Constants.ComponentVersions);

        FileWriter f = null;
        CSVWriter w = null;
        try {
            try {
                f= new FileWriter(fileName);
            }
            catch (IOException ioe) {
                Log.update.debug("Cannot update %s : %s",
                        fileName,ioe.getMessage());
                return false;
            }
            w = new CSVWriter(f);
            Iterator e = h.keySet().iterator();
            while (e.hasNext()) {
                String key = (String)e.next();
                String value = (String)h.get(key);
                List temp = ListUtil.list();
                temp.add(key);
                temp.add(value);
                w.writeLine(temp);
            }
        }
        finally {
            if (w!=null) {
                w.close();
            }
            IOUtil.close(f);
        }
        Log.update.debug(Fmt.S("Finished writing %s.",Constants.ComponentVersions));
        return true;
    }

    /**
        Backs up product.csv
    */
    private boolean backupProductCSV (String currentUpdateName)
    {
        String pcsvBackup = StringUtil.strcat(Fmt.S(
                                                  Constants.UpdateBackUp,path,currentUpdateName),Constants.ProductCSV);
        String psvInInstance =
            StringUtil.strcat(path,Constants.BOMLocationDir,
                              Constants.ProductCSV);

        if (!IOUtil.copyFile(new File(psvInInstance),
                             new File(pcsvBackup))) {

            Log.update.warning(6806,psvInInstance,pcsvBackup);
            return false;
        }
        return true;
    }

    /**
        Restores product.csv
    */
    private boolean restoreProductCSV (String currentUpdateName)
    {
        String pcsvBackup = StringUtil.strcat(Fmt.S(
                                                  Constants.UpdateBackUp,path,currentUpdateName),Constants.ProductCSV);
        String psvInInstance =
            StringUtil.strcat(path,Constants.BOMLocationDir,
                              Constants.ProductCSV);
        if (!IOUtil.copyFile(new File(pcsvBackup),new File(psvInInstance))) {
            Log.update.warning(6806,pcsvBackup,psvInInstance);
            return false;
        }
        return true;
    }
    /**
        Writes into product.csv.
        Each object in the vector is considered to be one line.
        If flag is true then content is appended to existing product.csv
    */
    private boolean writeProductCSV (List v, boolean flag)
    {
        if (v.isEmpty()) {
            return true;
        }
        String psvInInstance =
            StringUtil.strcat(path,Constants.BOMLocationDir,Constants.ProductCSV);
        FileWriter f = null;
        CSVWriter w = null;
        try {
            try {
                f= new FileWriter(psvInInstance,flag);
            }
            catch (IOException ioe) {
                Log.update.debug("writeProductCSV: Cannot update %s : %s",
                        psvInInstance,ioe.getMessage());
                return false;
            }
            w = new CSVWriter(f);
            for (int k=0;k<v.size();++k) {
                String compName = (String)v.get(k);
                Log.update.debug("writeProductCSV: updating product.csv with "+
                        "component %s", compName);
                List temp = ListUtil.list();
                temp.add(compName);
                w.writeLine(temp);
            }
        }
        finally {
            if (w!=null) {
                w.close();
            }
            IOUtil.close(f);
        }
        return true;
    }

    /**
        reads the product.csv and strips out the BOMS not needed
        for this platform
    */
    private List readProducrCSVAndStripBOM ()
    {
        List v = readProductCSV();
        if (v==null) {
            return null;
        }
        List returnVec = ListUtil.list();
        for (int i=0;i<v.size();++i) {
            String compName = (String)v.get(i);
            String osInExclude = (String)excludeBOM.get(compName);
            if ((osInExclude == null) ||
                osName.equals(osInExclude)) {
                    //implies entry for this componet is not there
                Log.update.debug(
                    Fmt.S("Component %s wont be excluded.",compName));
                returnVec.add(compName);
                continue;
            }
            else {
                Log.update.debug(
                    Fmt.S("Component %s will excluded for OS %s",
                          compName,osName));
            }
        }
        return returnVec;
    }
    /**
        Read product.csv and return a List of the first element in each line.
        The first element in each line is the name of the component.
    */
    private List readProductCSV ()
    {
        List v = null;
        List returnVec = ListUtil.list();
        String pFileName = StringUtil.strcat(path,Constants.BOMLocationDir,
                                             Constants.ProductCSV);
        File f = new File(pFileName);
        if (!f.exists()) {
            Log.update.debug(
                Fmt.S("readProductCSV: File %s does not exist.",pFileName));
            return v;
        }
        if (f.isDirectory()) {
            Log.update.debug(
                Fmt.S("readProductCSV: %s is a directory. It should be a file.",
                      pFileName));
            return v;
        }
            //Read contents of the file
        try {
            Log.update.debug("Reading product.csv file");
            v = CSVReader.readAllLines(new File(pFileName),
                                       SystemUtil.getFileEncoding());
        }
        catch (IOException ioe) {
            Log.update.debug(
                Fmt.S("readProductCSV: Error while reading file %s : %s",
                      pFileName, ioe.getMessage()));
            return null;
        }

        for (int i=0;i<v.size();++i) {
            List line = (List)v.get(i);
                //now the first element is assumed to be the component name.
            if (line.size()==0) {
                Log.update.debug(
                    "readProductCSV: Line with no content in product.csv file");
                continue;
            }
            String compName = (String)ListUtil.firstElement(line);
            returnVec.add(compName);
        }
        return returnVec;
    }

    /**
     * Determines it the warning message should be printed in case of Buyer
     * @param fileName The name of the file that's missing
     * @return true if warning needs to be printed
     */
    private boolean needToPrintWarning (String fileName)
    {
        if (CommonKeys.buyer.equalsIgnoreCase(product) && nowProcessing) {
            Iterator e = excludeFeatureFile.keySet().iterator();
            while (e.hasNext()) {
                String key = (String)e.next();
                if (fileName.startsWith(key)) {
                    return false;
                }
            }
        }
        return true;
    }
    /**
        This method goes thru the list of components present in the product.csv
        file and checks if the corresponding bom files are present.
        If flag is true then it loads the BOM file and checks if there is
        any active version of the component. if yes then it proceedes to check
        if the md5 and size of file on disk match with the one given in
        <file>
    */
    private int checkProduct (boolean flag)
    {
        int customizationCount = 0;
        List v = readProducrCSVAndStripBOM();
        if (v==null) {
            Log.update.warning(6850);
            return 0;
        }
        int errorCount = 0;
        for (int i=0;i<v.size();++i) {
            String compName = (String)v.get(i);
            Log.update.debug("checkproduct: processing component %s.",
                             compName);
            String pathToBOMFile = StringUtil.strcat(path,
                                                     Fmt.S(Constants.BOMLocation,compName));
            File temp = new File(pathToBOMFile);
            if (!temp.exists()) {
                Log.update.warning(6799,pathToBOMFile);
                ++errorCount;
                continue;
            }
            if (temp.isDirectory()) {
                Log.update.warning(6842,pathToBOMFile);
                ++errorCount;
                continue;
            }
            if (flag) {
                String activeVersion =
                    getActiveVersion(pathToBOMFile,compName,MapUtil.map());
                if (activeVersion == null) {
                    Log.update.warning(6772,compName,"",pathToBOMFile);
                    ++errorCount;
                    continue;
                }
                if (activeVersion.equals(Constants.NoActive)) {
                    Log.update.info(6841,
                                    Fmt.S("checkproduct: No active version for" +
                                          " %s in file %s.",
                                          compName,pathToBOMFile));
                    continue;
                }
                else {
                        //load component
                    ComponentElement c = getComponentInBOM(pathToBOMFile,
                                                           compName,activeVersion,false);
                    if (c==null) {
                        Log.update.warning(6809,compName,activeVersion,pathToBOMFile);
                        ++errorCount;
                        continue;
                    }
                    msg = Fmt.S("%s Verifying Size and MD5 hash component %s %s.",
                                String.valueOf(i),compName,activeVersion);
                    Log.update.info(6841,msg);
                    if (!verifyFileSizeAndHashOfComponent(c)) {
                        Log.update.info(6839,compName);
                        ++customizationCount;
                    }
                }
            }

        }
        if (errorCount!=0) {
                //errorCount is not incremenented for file customizations.
            return 0;
        }
        if (customizationCount!=0) {
                //Print a message saying that some customizations have been made and
                //that these files may be over written if there is a newer version of the file,
                //in which case the customized files will be backed up.
            console(
                Fmt.S("One or more files have been changed in the %s instance. To take "+
                    "a look at the list of files please see the log "+
                    " file logs/Harnesslog.txt.",product));
            System.out.flush();
            if (!silentMode) {
                if (!Util.readUserInputUntilEquals("Do you wish to continue(y/n): ","y","n")) {
                    return 1;
                }
            }
        }
        return 2;
    }

    /**
        Given a ComponentElement this method checks if all the files exist
        and are writable since they need to be moved
    */
    private boolean verifyFileSizeAndHashOfComponent (ComponentElement c)
    {
        int errorCount = 0;


        List v = c.getFiles();
        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            String pathToFile = StringUtil.strcat(path,Constants.fs,
                                                  fl.getPath());
                //Check if this is one of those files that just needs
                //to be ignored only for validation. Eg BuildInfo.csv
            String osToExclude = (String)ignoreFile.get(fl.getPath());
            if ((osToExclude!=null) && (!osName.equals(osToExclude))) {
                Log.update.debug(
                    Fmt.S("Ignoring file %s from component %s",
                          fl.getPath(),c.getComponentName()));
                continue;
            }
            File f = new File(pathToFile);
            if (!f.exists()) {
                if (needToPrintWarning(fl.getPath())) {
                    Log.update.warning(6799,pathToFile);
                    ++errorCount;
                }
                continue;
            }

            if (!verifyFileSizeAndHash(pathToFile,fl.getMD5(),fl.getSize())) {
                Log.update.info(6843,pathToFile);
                ++errorCount;
            }
        }
        if (errorCount!=0) {
            return false;
        }
        return true;
    }

    /**
        Goes thru each BOM file in the instance (whatever is there in product.csv)
        and determines the
        dependent components for each component that's active.
        If an active component is being uninstalled (if listed in uninstallHash) then it will
        determine the dependents of the next active component.
    */
    private boolean initializeBOMDependencies (Map dependencyHash,
                                               Map uninstallHash,
                                               Map updateHash)
    {
        int errorCount = 0;
        String componentsDir = StringUtil.strcat(path,
                                                 Constants.BOMLocationDir,Constants.fs,"%s.bom");
        List comps = readProducrCSVAndStripBOM();
        if (comps==null) {
            Log.update.warning(6850);
            return false;
        }
        for (int i=0;i<comps.size();++i) {
            String compName = (String)comps.get(i);
            String bomFileName= Fmt.S(componentsDir,compName);

                //If this component is being updated then there is no need to go thru the dependencies
                //in the bom file for the component. This is because we need to go thru the dependencies for this
                //component for the new version that's coming it. This has already been done at this point when
                //we go thru the list of component's coming in with this update.
                //arounf line 845

            if (updateHash.containsKey(compName)) {
                Log.update.debug(Fmt.S("Dependency already evaluated for %s",compName));
                continue;
            }

            /*
                Note: This method returns the active version of the component
                This includes the case if the current active version is being
                uninstalled (specified in the uninstallHash), in which case
                it returns the next active version if any. Hence we don't need
                an additional check to see if we are checking for dependencies
                of a version that's being uninstalled (i.e. getActiveVersion() returns
                only the current active version not some version that is active and might get
                uninstalled)
            */
            String activeVersion = getActiveVersion(bomFileName,compName,uninstallHash);
            if (activeVersion == null) {
                Log.update.warning(6772,compName,"",bomFileName);
                ++errorCount;
                continue;
            }
            if (activeVersion.equals(Constants.NoActive)) {
                Log.update.debug("initializeBOMDependencies: No active version for %s in file %s.",
                                 compName,bomFileName);
            }
            else {

                ComponentElement c = getComponentInBOM(bomFileName,
                                                       compName,activeVersion,false);
                if (c==null) {
                    Log.update.warning(6809,compName,activeVersion,bomFileName);
                    ++errorCount;
                    continue;
                }
                /*
                    This is the list of dependencies for this component.
                    There is no version number so we will use "*" ie.
                    any version is fine as long as it will be active"
                */
                Log.update.debug(
                    Fmt.S("Initializing BOM dependencies for component %s %s",
                          compName,activeVersion));
                List v = c.getDependencies();
                for (int j=0;j<v.size();++j) {
                    String componentName = (String)v.get(j);
                    String key = StringUtil.strcat(componentName,"|","*");
                    dependencyHash.put(key,"*");
                }
            }
        }
        if (errorCount!=0) {
            return false;
        }
        return true;
    }

    /**
        This method returns the list of currently "installed" updates in the system.
        Each element in the vector actually contains a org.w3c.dom.Element, which
        represnt s the <update> in the manifest file.

    */
    private List getListOfUpdates ()
    {
        int errorCount = 0;
        List v = ListUtil.list();
        String updatesDir = StringUtil.strcat(path,
                                              Constants.UpdatesDir);
            //get list of all directories in the above directory
        File f = new File(updatesDir);
        if (!f.exists()) {
            Log.update.debug("getListOfUpdates: Getting list of updates: %s does not exist",
                             updatesDir);
            return v;
        }

        String[] list = f.list();
        for (int i=0;i<list.length;++i) {

            Log.update.debug("getListOfUpdates: Getting list of updates: Processing update %s",
                             list[i]);
            String manifestDir =
                StringUtil.strcat(updatesDir,list[i],Constants.fs);
            File dir = new File(manifestDir);
                //There might be other files in the "updates" directory. Make sure you
                //only process the directories
            if (!dir.isDirectory()) {
                Log.update.debug("getListOfUpdates: Getting list of updates: %s is not a directory",
                                 manifestDir);
                continue;
            }
            String manifestFile =
                StringUtil.strcat(manifestDir,list[i],".xml");
            File file = new File(manifestFile);
                //Manifest file should exist. Else error
            if (!file.exists()) {
                Log.update.warning(6799,manifestFile);
                ++errorCount;
                continue;
            }
                //load file check if the status is !installed
            Element rootElement = Util.readXMLFile(manifestFile,true);
            if (rootElement == null) {
                    //could not load UM file
                Log.update.warning(6758,manifestFile);
                ++errorCount;
                continue;
            }

            NodeList nl = Util.getNodeList(rootElement, Constants.UpdateTag);
            if ((nl==null) || (nl.getLength()!=1)) {
                Log.update.warning(6786,Constants.UpdateTag,
                                   manifestFile);
                ++errorCount;
                continue;
            }
            Element update = (Element)nl.item(0);
            if (Constants.UpdateStatusInstalled.equals(
                    update.getAttribute(Constants.StatusAttr))) {
                v.add(update);
            }

        }
        if (errorCount != 0) {
            return null;
        }
        return v;
    }


    /**
        this method checks for the following:
        If the files being installed are present in the depot.
        If the files being backed up (because another version is getting
        installed) can be backed up (i.e.copied to another location and
        deleted froom the current location. The backup directory structure
        will be created)
    */
    private boolean checkIfOkToInstall (Map realUpdateHash)
    {
        int errorCount=0;


        Iterator uuh = realUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compName = (String)uuh.next();
            ComponentElement c = (ComponentElement)realUpdateHash.get(compName);
            if (!checkIfOkToInstallComponent(c)) {
                Log.update.warning(6844,c.getComponentName(),c.getVersion());

                ++errorCount;
            }
        }
        if (errorCount!=0) {
            return false;
        }
        return true;
    }

    /**
        Method checks if a component is okay to be insntalled
    */
    private boolean checkIfOkToInstallComponent (ComponentElement c)
    {
        int errorCount = 0;

        print(Fmt.S("Checking to see if component %s %s can be "+
                    " installed.",c.getComponentName(),c.getVersion()));

        String currentActiveVersion = c.getCICStatus();
        /*
            No files to be backed up if this is a new component OR if there
            is no active version of this component in the instance
        */
        if ((currentActiveVersion!=null) &&
            (!currentActiveVersion.equals(Constants.NoActive))) {


            if (!checkIfFilesAreWritable(c.getNextActive())) {
                ++errorCount;
            }
        }
        /*
            Check if files for this component are in the depot.
            Note : Files in depot have the "docroot" since there might be
            some files with same name and directory structure
            in both coreserver and webcomponents
        */
        String pathToDepot = Fmt.S(ComponentInDepotDir,c.getComponentName(),
                                   c.getVersion());
        if (!nowProcessing) {
            pathToDepot = StringUtil.strcat(pathToDepot,
                                            Constants.DocRoot,Constants.fs);
        }

        if (!checkIfFileExists(c,
                               pathToDepot)) {

            ++errorCount;
        }
            //Also create theh directory structure for the files being brought
            //in from the depot in case the directory structure does not exist in the instance
        if (!createDirectoryStructure(c,path)) {
            Log.update.debug(
                Fmt.S("checkIfOkToInstall: A problem occurred while creating the directory structure for"+
                      " files in component %s %s.",
                      c.getComponentName(),c.getVersion()));
            ++errorCount;
        }

        if (errorCount != 0) {
            return false;
        }
        return true;
    }

    /**
        Two step process. First delete all files that were brought in for all
        components. Then bring back all files for all components that were backed up.
        if newComponent is true then it means product.csv needs to be restored.
    */
    private boolean installRollback (Map realUpdateHash,boolean newComponent,
                                     String currentUpdateName, List restoredComponents)
    {

        Iterator e = realUpdateHash.keySet().iterator();
        while (e.hasNext()) {
            String compName = (String)e.next();
            ComponentElement c = (ComponentElement)realUpdateHash.get(compName);
            if (Constants.IntStatusProcessed.equals(c.getInternalStatus())) {
                print(Fmt.S("Rolling  back installed component %s %s.",
                            compName,c.getVersion()));

                if (!deleteFiles(c,path)) {
                    Log.update.warning(6814,compName,
                                       c.getVersion());
                    Log.update.warning(6819,compName,c.getVersion());
                    return false;
                }
                c.setInternalStatus(Constants.IntStatusNotProcessed);
            }
        }
        e = realUpdateHash.keySet().iterator();
        while (e.hasNext()) {
            String compName = (String)e.next();
            ComponentElement c = (ComponentElement)realUpdateHash.get(compName);

            /*
                Version active on the customers instance prior to to this
                component being installed.
            */
            String activeVersion = c.getCICStatus();
            /*
                Restore files only if
                activeVersion!= null && activeVersion!="no active"
            */
            ComponentElement activeElement = null;
            if ((activeVersion!=null) &&
                (!activeVersion.equals(Constants.NoActive))) {

                /*
                    ComponentElement active prior to this component being installed
                */
                activeElement = c.getNextActive();
                if (Constants.IntStatusProcessed.equals(
                        activeElement.getInternalStatus())) {
                    String backupLocation = Fmt.S(Constants.ComponentBackUp,path,
                                                  compName,activeElement.getVersion());
                    print(Fmt.S("Restoring component %s with version"+
                                "%s because installation of %s failed.",compName,activeVersion,
                                c.getVersion()));

                    if (!restoreFiles(activeElement,backupLocation,path,false)) {

                        Log.update.debug(
                            Fmt.S("installRollback: Could not restore the files successfully for %s %s",
                                  compName,activeElement.getVersion()));
                        Log.update.warning(6819,compName,c.getVersion());
                        return false;
                    }
                }
                activeElement.setInternalStatus(Constants.IntStatusNotProcessed);
            }
            /*
                Delete the existing BOM file in the instance and copy
                the one that was backed up if needed
            */
            if (activeVersion!=null) {
                String updateBackup = Fmt.S(Constants.UpdateBackUp,path,currentUpdateName);
                updateBackup = StringUtil.strcat(updateBackup,compName,".bom");
                File backupFile = new File(updateBackup);
                if (backupFile.exists()) {
                    String pathToBOMFileIninstance = StringUtil.strcat(path,
                                                                       Fmt.S(Constants.BOMLocation,compName));
                    File f = new File(pathToBOMFileIninstance);
                    if (f.exists()) {
                        if ((!f.delete()) && (activeVersion==null)) {
                            Log.update.warning(6805,pathToBOMFileIninstance);
                            Log.update.warning(6819,compName,c.getVersion());
                            return false;
                        }
                    }

                    if (!IOUtil.copyFile(backupFile,f)) {
                        Log.update.warning(6806,updateBackup,pathToBOMFileIninstance);
                        Log.update.warning(6819,compName,c.getVersion());
                        return false;
                    }
                }
            }
        }
        /*
            Copy back the product.csv if it was backed up
        */
        if ((newComponent) || (restoredComponents.size()!=0)) {
            if (!restoreProductCSV(currentUpdateName)) {
                return false;
            }
        }

        /*
            Bring back he manifest files for the restored updates
        */
        /*if (restoredUpdates.size()!=0) {
            for (int f=0;f<restoredUpdates.size();++f) {
            String restoredUpdateName =(String)restoredUpdates.get(f);

            String fileInBackupLocation = StringUtil.strcat(
            Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
            restoredUpdateName,".xml");
            File bacupLocationFile = new File(fileInBackupLocation);

            String manifestName = Fmt.S(updateLocation,
            restoredUpdateName,restoredUpdateName);
            Log.update.debug(
            Fmt.S("installRollback: Bringing back the backedup manifest file for the update"+
            " %s, which got rolled back.",restoredUpdateName));
            if (!IOUtil.copyFile(bacupLocationFile,new File(manifestName))) {
            Log.update.warning(6806,fileInBackupLocation,manifestName);
            return false;
            }
            }
            }
        */

        return true;
    }

    /**
        To install a component the files coming in as part of the component
        have to be backed up first if they exist on the customers instance.
        Also the BOM file for the component if it exists in the instance will be backed up
        to etc/backup/updates/<current_update>/
        These files would be backed up under the current active version of the component.
        Eg.
        Consider that component a.b, version=12.5 is coming in with this update.
        Lest say the current active version of a.b on the customers instance is 12.4

        Also lets say a.b-12.5 has the following file
        classes/a.b.zip

        Now if this file is present on the customers instance then its assumed its version
        is 12.4 since that's the current active version.
        hence this file gets backed up to etc/backup/components/12.4/classes/a.b.zip
        only if it has changed

        Also if this is a new component then there is no files to back up.
        Also if this component has no active version (cause all versions are
        superceded because of uninstalls) on disk then there are
        no files to be backed up since they would have been backed up at the time of
        uninstall.

        Once backup is done if needed the current component files are
        brought in from depot. Then the BOM in the customers instance is updated
        to reflect the status of the new component.

        Once all the components are updated, the UM file for the update is copied
        to the instance and its status is updated to "installed"

        Its a two step process. First all files of all components that
        need to be backedu are backed up. Then all files of all components that
        need to be brought in are brought in from the depot.

    */

    private boolean install (Map realUpdateHash, String umFileName,
                             String currentUpdateName,List restoredComponents)
    {
        String activeVersion = null;
        List newComponents = ListUtil.list();
        String UMFileLocation = Fmt.S(Constants.partialUmFilePath,umFileName);

            //go thru the hash and do the backup first
        Iterator e = realUpdateHash.keySet().iterator();
        while (e.hasNext()) {
            activeVersion=null;
            String compName = (String)e.next();
            ComponentElement c = (ComponentElement)realUpdateHash.get(compName);
            String pathToBOMFileIninstance = StringUtil.strcat(path,
                                                               Fmt.S(Constants.BOMLocation,compName));
            activeVersion = c.getCICStatus();
            /*
                Look at the description of the cicStatus variable in ComponentElement
            */
            if (activeVersion == null) {
                Log.update.debug(
                    Fmt.S("install:  This %s is a new Component. Nothing to back up",
                          compName));
                newComponents.add(compName);
            }
            else {
                    //Backup the BOM file since it exists. Note BOM filee can conatin
                    //a status of superceded for all versions. In this case
                    //activeVersion="noactive"
                Log.update.debug("install: BOM file exists for component %s %s"+
                                 " Will be backed up",compName,activeVersion);
                String updateBackup = Fmt.S(Constants.UpdateBackUp,path,currentUpdateName);
                updateBackup = StringUtil.strcat(updateBackup,compName,".bom");
                if (!IOUtil.copyFile(new File(pathToBOMFileIninstance),
                                     new File(updateBackup))) {
                    Log.update.warning(6806,pathToBOMFileIninstance,updateBackup);
                    return false;
                }

                if (activeVersion.equals(Constants.NoActive)) {
                        //Nothing to backup since there is no active version of the component
                    Log.update.info(6841,
                                    Fmt.S("install: Nothing to backup since no version of %s is active",
                                          compName));
                    activeVersion = null;
                }
                else {
                    ComponentElement currentActive = c.getNextActive();
                    String backpUpDir =
                        Fmt.S(Constants.ComponentBackUp,path,compName,activeVersion);

                    /*
                        backup files of the component that will be replaced.
                        even if it fails mark it as processed since we will be
                        checking at file level during rollback.
                    */
                    if (!copyFiles(currentActive,path,backpUpDir,false,true,true)) {
                        currentActive.setInternalStatus(Constants.IntStatusProcessed);
                        Log.update.warning(6845,activeVersion,compName,c.getVersion());
                        return false;
                    }
                    currentActive.setInternalStatus(Constants.IntStatusProcessed);
                }
            }
        }
            //go thru the hash again and do the actual install
        e = realUpdateHash.keySet().iterator();
        while (e.hasNext()) {
            activeVersion=null;
            String compName = (String)e.next();
            ComponentElement c = (ComponentElement)realUpdateHash.get(compName);
            String pathToBOMFileIninstance = StringUtil.strcat(path,
                                                               Fmt.S(Constants.BOMLocation,compName));
            activeVersion = c.getCICStatus();

            if ((activeVersion==null) ||
                (activeVersion.equals(Constants.NoActive))) {
                    //nothing currenttly active. so set it to null so that updateBOM()
                    //dosent croak
                activeVersion = null;
            }

            String backupLocation = Fmt.S(Constants.ComponentBackUp,path,
                                          compName,c.getVersion());

            print(Fmt.S("Bringing in files for component %s %s.",
                        compName,c.getVersion()));
                //now copy the files over from the depot
            if (!restoreFiles(c,backupLocation,path,true)) {
                c.setInternalStatus(Constants.IntStatusProcessed);
                Log.update.warning(6846,compName,c.getVersion());
                return false;
            }

            /*
                Now update BOM in instance. 2 Steps
                Set currently active version to superceded.
                Merge contents of BOM in depot/base image to BOM in instance
            */
            Log.update.debug(
                Fmt.S("install: Changing status for %s in BOM. %s is being set to superceded",
                      compName,activeVersion));
            if (!updateBOM(compName,activeVersion,null)) {
                Log.update.warning(6847,compName);
                return false;
            }
                //step 2
                //the boms will either be in the depot (99% of the cases) or in the image
                //(very rare. happens only if we uninstalled a component with the first SP
                //and then we uninstall this SP thereby restoring the uninstalled component.
                //in such case the uninstalled component wont have a bom in the depot. will be
                //there in the base image. i need to do this becausue we don't ship componentized
                //base image.
            String baseImageVersion = getVesrionInBaseImageBOM(c.getComponentName());
            String bomInDepot = getBOMLocation(compName,c.getVersion(),baseImageVersion);

            if (bomInDepot==null) {
                Log.update.warning(6916,compName,c.getVersion());
                return false;
            }

            Log.update.debug(
                Fmt.S("install: Changing status for %s in BOM. %s is being set to active",
                      compName,c.getVersion()));
            if (!mergeTwoBOMS(c,bomInDepot,pathToBOMFileIninstance)) {
                return false;
            }
            c.setInternalStatus(Constants.IntStatusProcessed);

        }
            // update the product.csv
        if ((newComponents.size()!=0) || (restoredComponents.size()!=0)) {
            Log.update.debug("install: updating product.csv since there"+
                             " are %s new components",
                             ariba.util.core.Constants.getInteger(newComponents.size()));
            if (!writeProductCSV(newComponents,true)) {
                Log.update.warning(6855);
                return false;
            }
            if (!writeProductCSV(restoredComponents,true)) {
                Log.update.warning(6855);
                return false;
            }
        }

            //update the manifest for the restored updates
        String umInInstance = null;
        /*if (restoredUpdates.size()!=0) {
            for (int f=0;f<restoredUpdates.size();++f) {
            String restoredUpdateName =(String)restoredUpdates.get(f);
            umInInstance =
            Fmt.S(updateLocation,restoredUpdateName,restoredUpdateName);
            if (!updateManifestFile(umInInstance,
            umInInstance,Constants.UpdateStatusInstalled)) {

            Log.update.info(6807,
            Constants.UpdateStatusInstalled,
            umInInstance);
            return false;
            }
            }
            }
        */


        /*
            now copy the UM file for this update into the update directory
            change the status in the UM file
        */
        umInInstance = Fmt.S(updateLocation,currentUpdateName,umFileName);
        Log.update.debug("install: updating manifest file %s with status "+
                         " %s.",umInInstance,Constants.UpdateStatusInstalled);

        if (!updateManifestFile(UMFileLocation,
                                umInInstance,Constants.UpdateStatusInstalled)) {

            Log.update.warning(6807,
                               Constants.UpdateStatusInstalled,
                               umInInstance);
            /*
                xxx: Not sure if its wise to roll back. Maybe just print out a
                message asking the cusutomer to manually copy the file and update the
                status.
            */
            return false;
        }

        return true;
    }
    /*
        Takes the component tag in bom1 (which should be only one)
        and puts it into bom2. If this this tag is already present in bom2
        then delete it and put the new one in
        version is the version ogo the component in bom1
    */
    private boolean mergeTwoBOMS (ComponentElement c, String bom1, String bom2)
    {

        String version = c.getVersion();
        String compName = c.getComponentName();
        Log.update.debug(
            Fmt.S("mergeTwoBOMS : Merging BOM for component %s. Files being merged are "+
                  "%s and %s", compName,bom1,bom2));

        Element rootElement2 = null;
        Element rootElement1 = Util.readXMLFile(bom1,false);
        if (rootElement1 == null) {
                //could not load BOM file
            Log.update.warning(6766,bom1);
            return false;
        }

        NodeList nl = Util.getNodeList(rootElement1, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()!=1)) {
            Log.update.warning(6786,Constants.ComponentTag,bom1);
            return false;
        }
        Node update = nl.item(0);
        Element updateTempElement = (Element)update;
        updateTempElement.setAttribute(Constants.TimeAttr,getTime());


        File f = new File(bom2);
        if (!f.exists()) {
            rootElement2 = rootElement1;
        }
        else {
            rootElement2 = Util.readXMLFile(bom2,false);
            if (rootElement2 == null) {
                    //could not load BOM file
                Log.update.warning(6766,bom2);
                return false;
            }

            nl = Util.getNodeList(rootElement2,Constants.ComponentTag);
            if (nl==null) {
                Log.update.warning(6767,Constants.ComponentTag,Constants.BomTag,
                                   bom2);
                return false;
            }
            for (int i=0;i<nl.getLength();++i) {
                Element temp = (Element)nl.item(i);
                String tempVersion = temp.getAttribute(Constants.VersionAttr);
                String tempStatus = temp.getAttribute(Constants.StatusAttr);
                if (version.equals(tempVersion)) {
                    Log.update.debug("mergeTwoBOMS for %s : Found version "+
                                     "%s and status %s. Will be replaced with version %s and status %s"+
                                     " from file %s",
                                     compName,tempVersion,tempStatus,version,
                                     updateTempElement.getAttribute(Constants.StatusAttr),
                                     bom1);
                    rootElement2.removeChild(temp);
                }
            }
                //Node needs to be imported to 2nd document since its owner is different.
            Document d = rootElement2.getOwnerDocument();
            Node n = d.importNode(update,true);
            rootElement2.appendChild(n);
        }
        if (!saveXMLToFile(bom2,rootElement2)) {
            Log.update.warning(6808,bom2);
            return false;
        }
        return true;
    }
    /**
        Backs up the manifest dtd from the current update in depot to the
        specifieid path
    */
    private boolean backupManifestDTD (String backup)
    {
        backup = StringUtil.strcat(backup,"um.dtd");
        String origin = StringUtil.strcat("tasks",Constants.fs,"um.dtd");
        if (!IOUtil.copyFile(new File(origin), new File(backup))) {
            Log.update.warning(6806,origin,backup);
            return false;
        }
        return true;
    }

    /**
        Backs up components that are being uninstalled.
        Pointer to functions would have helped here!!!
    */
    private boolean uninstallBackup (Map uninstallUpdateHash,
                                     String currentUpdateName)
    {
        ComponentElement c = null;
        Iterator uuh = uninstallUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compUpKey = (String)uuh.next();
            Object o = uninstallUpdateHash.get(compUpKey);
            if (o instanceof ComponentElement) {
                c = (ComponentElement)o;


                if (!backupUninstallComponents(c,currentUpdateName)) {
                    Log.update.warning(6846,c.getComponentName(),c.getVersion());
                    return false;
                }
            }
            else if (o instanceof UpdateElement) {
                UpdateElement u = (UpdateElement)o;
                print(
                    Fmt.S("Backing up  components in update %s",
                          u.getUpdateName()));
                    //Backup the Manifest file for the update being uninstalled
                String backupLocation = StringUtil.strcat(
                    Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
                    u.getUpdateName(),".xml");
                File bacupLocationFile = new File(backupLocation);

                String manifestName = Fmt.S(updateLocation,
                                            u.getUpdateName(),u.getUpdateName());
                Log.update.debug(
                    Fmt.S("uninstallBackup: Backing up the manifest file for update %s",
                          u.getUpdateName()));
                if (!IOUtil.copyFile(new File(manifestName), bacupLocationFile)) {
                    Log.update.warning(6806,manifestName,backupLocation);
                    return false;
                }
                    /////////////////////////////////////////////////////////////////////////
                    //Also backup the manifest dtd to the same directory. Remove this
                    //code once the update installer starts to change the DOCTYPE
                    //decleration in the manifest file to make it refer to the right dtd
                    //location.//////////////////////////////////////////////////////////////
                if (!backupManifestDTD(Fmt.S(Constants.UpdateBackUp,path,currentUpdateName))) {
                    return false;
                }

                Map h = u.getComponents();
                Iterator k = h.keySet().iterator();
                while (k.hasNext()) {
                    String compName = (String)k.next();
                    c = (ComponentElement)h.get(compName);
                    if (!backupUninstallComponents(c,currentUpdateName)) {
                        u.setInternalStatus(Constants.IntStatusBackedup);
                        Log.update.warning(6846,c.getComponentName(),c.getVersion());
                        return false;
                    }
                }
                u.setInternalStatus(Constants.IntStatusBackedup);
            }
        }
        return true;
    }

    /**
        Brings in components that are being brought in as part of
        restoration.
    */
    private boolean uninstallRestore (Map uninstallUpdateHash)
    {
        ComponentElement c = null;
        List removeComponents = ListUtil.list();
        Iterator uuh = uninstallUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compUpKey = (String)uuh.next();
            Object o = uninstallUpdateHash.get(compUpKey);
            if (o instanceof ComponentElement) {
                c = (ComponentElement)o;

                if (c.getNextActive() == null) {
                    removeComponents.add(c.getComponentName());
                }
                else {
                    print(
                        Fmt.S("Restoring component %s %s",
                              c.getNextActive().getComponentName(),
                              c.getNextActive().getVersion()));
                }

                if (!restoreUninstallComponents(c)) {
                    Log.update.warning(6846,c.getComponentName(),c.getVersion());
                    return false;
                }
            }
            else if (o instanceof UpdateElement) {
                UpdateElement u = (UpdateElement)o;
                print(
                    Fmt.S("Restoring components in update %s",
                          u.getUpdateName()));


                Map h = u.getComponents();
                Iterator k = h.keySet().iterator();
                while (k.hasNext()) {
                    String compName = (String)k.next();
                    c = (ComponentElement)h.get(compName);
                    if (c.getNextActive() == null) {
                        removeComponents.add(c.getComponentName());
                    }
                    else {
                        print(
                            Fmt.S("Restoring component %s %s in update",
                                  c.getNextActive().getComponentName(),
                                  c.getNextActive().getVersion(),
                                  u.getUpdateName()));
                    }
                    if (!restoreUninstallComponents(c)) {
                        u.setInternalStatus(Constants.IntStatusProcessed);
                        Log.update.warning(6846,c.getComponentName(),c.getVersion());
                        return false;
                    }
                }
                /*
                    This means the update has been uninstalled.
                    update the corresponding manifest file to status=uninstalled
                */
                String manifestName = Fmt.S(updateLocation,
                                            u.getUpdateName(),u.getUpdateName());
                Log.update.debug(
                    Fmt.S("uninstallRestore: updating manifest file %s with status "+
                          " %s.",manifestName,Constants.UpdateStatusUnInstalled));
                if (!updateManifestFile(manifestName,manifestName,
                                        Constants.UpdateStatusUnInstalled)) {

                    Log.update.warning(6807,
                                       Constants.UpdateStatusUnInstalled,
                                       manifestName);
                    u.setInternalStatus(Constants.IntStatusProcessed);
                    return false;
                }
                u.setInternalStatus(Constants.IntStatusProcessed);
            }
        }

        /**
            Remove components from product.csv if any.
            First read everything into a vector and then delete
            the ones that a re not needed and write it back into product.csv
        */
        if (removeComponents.size()!=0) {
            List v = readProductCSV();
            if (v==null) {
                Log.update.warning(6850);
                return false;
            }
            for (int i=0; i<removeComponents.size();++i) {
                String compName = (String)removeComponents.get(i);
                Log.update.debug(
                    Fmt.S("uninstallPartTwo: Component %s will be removed from product.csv",compName));
                if (v.contains(compName)) {
                    v.remove(compName);
                }
            }
            if (!writeProductCSV(v,false)) {
                Log.update.warning(6855);
                return false;
            }
        }
        return true;
    }
    /**
        This method backs up files(if needed) for a component that being uninstalled.
    */
    private boolean backupUninstallComponents (ComponentElement c,
                                               String currentUpdateName)
    {

            //backup the BOM file if needed.
        String bomBackUpPath = Fmt.S(Constants.UpdateBackUp,path,currentUpdateName);
        bomBackUpPath = StringUtil.strcat(bomBackUpPath,c.getComponentName(),".bom");
        File tempBackup = new File(bomBackUpPath);
        if (!tempBackup.exists()) {
            String pathToBOMFile = StringUtil.strcat(path,
                                                     Fmt.S(Constants.BOMLocation,c.getComponentName()));
            Log.update.debug("backupUninstallComponents: Backing up BOM file %s to %s",
                             pathToBOMFile, bomBackUpPath);
            File tempCurrent = new File(pathToBOMFile);
            if (!IOUtil.copyFile(tempCurrent, tempBackup)) {
                Log.update.warning(6806,pathToBOMFile,bomBackUpPath);
                return false;
            }

        }
        else {
            Log.update.debug("backupUninstallComponents: BOM file will not be backed up",
                             " as a backup %s exists.", bomBackUpPath);
        }
        String backupLocation = Fmt.S(Constants.ComponentBackUp,path,
                                      c.getComponentName(),c.getVersion());

        if (!copyFiles(c,path,backupLocation,false,true,true)) {
            c.setInternalStatus(Constants.IntStatusProcessed);
            Log.update.warning(6846,c.getComponentName(),c.getVersion());
            return false;
        }

        c.setInternalStatus(Constants.IntStatusProcessed);
        return true;
    }

    /**
        This method restores files for a component that had previously
        been uninstalled (a component that's in a superceded state).
    */
    private boolean restoreUninstallComponents (ComponentElement c)
    {
            //Now if see if another component needs to be restored
        ComponentElement nextActive = c.getNextActive();
        String nextActiveVersion = null;
        if (nextActive!=null) {
            nextActiveVersion = nextActive.getVersion();

                //restore files from backup to current instance
            String backupLocation = Fmt.S(Constants.ComponentBackUp,path,
                                          nextActive.getComponentName(),nextActive.getVersion());

            if (!restoreFiles(nextActive,backupLocation,path,true)) {
                nextActive.setInternalStatus(Constants.IntStatusProcessed);
                Log.update.warning(6848,nextActive.getComponentName(),
                                   nextActive.getVersion());
                return false;
            }

            nextActive.setInternalStatus(Constants.IntStatusProcessed);
        }
            //update the BOM file located in <instance>/ariba/components
        Log.update.debug(
            Fmt.S("restoreUninstallComponents: Changing status for %s in BOM. %s is being set to"+
                  " Active %s is being set to superceded",c.getComponentName(),nextActiveVersion,
                  c.getVersion()));
        /*if (nextActiveVersion==null) {
            if (!updateBOM(c.getComponentName(),c.getVersion(),nextActiveVersion,
            true)) {
            Log.update.info(6847,c.getComponentName());
            return false;
            }
            }
            else {*/
        if (!updateBOM(c.getComponentName(),c.getVersion(),nextActiveVersion)) {
            Log.update.warning(6847,c.getComponentName());
            return false;
        }
            //}
        return true;
    }


    /**
        On successful uninstall of all components this method returns true.
        Else it return false.

        If uninstalling fails at any point an attempt will be made to roll back
        everything since it does not make sense to leave something uninstalled.
        This would mean the BOM files in the instance as well as the

        Uninstallation is a two step process. First all the files for all components
        that need to be uninstalled are backedup if needed. Then all files for  all components
        that need to be restored are brought in from the backup and depot for
        all components.

        If removeComponent is true then product.csv will need to be restored
        if uninstall fails.
    */

    private boolean uninstall (Map uninstallUpdateHash,boolean removeComponent,
                               String currentUpdateName)
    {
        if (!uninstallBackup(uninstallUpdateHash,currentUpdateName)) {
            Log.update.warning(6856);
            if (!rbUninstallBackup(uninstallUpdateHash,currentUpdateName)) {
                Log.update.warning(6858);
            }
            return false;
        }
        if (!uninstallRestore(uninstallUpdateHash)) {
            Log.update.warning(6857);
            if (!rbUninstallRestore(uninstallUpdateHash,removeComponent,
                                    currentUpdateName)) {
                Log.update.warning(6859);
                return false;
            }
            if (!rbUninstallBackup(uninstallUpdateHash,currentUpdateName)) {
                Log.update.warning(6858);
            }
            return false;
        }
        return true;
    }

    /**
        This method tries to roll back all unistall components that were backed
        up.
    */
    private boolean rbUninstallBackup (Map uninstallUpdateHash,
                                       String currentUpdateName)
    {
        ComponentElement c = null;
        Iterator uuh = uninstallUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compUpKey = (String)uuh.next();
            Object o = uninstallUpdateHash.get(compUpKey);
            if (o instanceof ComponentElement) {
                c = (ComponentElement)o;
                print(
                    Fmt.S("rbUninstallBackup: Rolling back uninstalled component %s %s",
                          c.getComponentName(),c.getVersion()));
                if (!rbUninstallBackupComponent(c,currentUpdateName)) {
                    Log.update.warning(6846,c.getComponentName(),c.getVersion());
                    return false;
                }
            }
            else if (o instanceof UpdateElement) {
                UpdateElement u = (UpdateElement)o;
                if (Constants.IntStatusBackedup.equals(u.getInternalStatus())) {
                    print(
                        Fmt.S("rbUninstallBackup: Rolling back uninstalled update %s",
                              u.getUpdateName()));

                    Map h = u.getComponents();
                    Iterator k = h.keySet().iterator();
                    while (k.hasNext()) {
                        String compName = (String)k.next();
                        c = (ComponentElement)h.get(compName);
                        print(
                            Fmt.S("rbUninstallBackup: Rolling back unistalled component %s %s"+
                                  " in update %s", c.getComponentName(),c.getVersion(),
                                  u.getUpdateName()));
                        if (!rbUninstallBackupComponent(c,currentUpdateName)) {
                            Log.update.warning(6846,c.getComponentName(),c.getVersion());
                            return false;
                        }
                    }

                        //Bring back the manifest file for rolled back uninstalled update from the backup location

                    String fileInBackupLocation = StringUtil.strcat(
                        Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
                        u.getUpdateName(),".xml");
                    File bacupLocationFile = new File(fileInBackupLocation);

                    String manifestName = Fmt.S(updateLocation,
                                                u.getUpdateName(),u.getUpdateName());
                    Log.update.debug(
                        Fmt.S("rbUninstallBackup: Bringing back the backedup manifest file for the update"+
                              " %s, which got rolled back.",u.getUpdateName()));
                    if (!IOUtil.copyFile(bacupLocationFile,new File(manifestName))) {
                        Log.update.warning(6806,fileInBackupLocation,manifestName);
                        return false;
                    }
                    u.setInternalStatus(Constants.IntStatusNotProcessed);
                }
            }
        }
        return true;
    }

    /**
        This method tries to roll back all components that were restored
        when another component was uninstalled.
        If removeComponent is true then product.csv is restored.
    */
    private boolean rbUninstallRestore (Map uninstallUpdateHash,
                                        boolean removeComponent, String currentUpdateName)
    {
        ComponentElement c = null;
        Iterator uuh = uninstallUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compUpKey = (String)uuh.next();
            Object o = uninstallUpdateHash.get(compUpKey);
            if (o instanceof ComponentElement) {
                c = (ComponentElement)o;
                if (c.getNextActive()!=null) {
                    print(
                        Fmt.S("rbUninstallRestore: Rolling back restored component %s %s",
                              c.getNextActive().getComponentName(),
                              c.getNextActive().getVersion()));
                }

                if (!rbUninstallRestoreComponent(c)) {
                    Log.update.warning(6846,c.getComponentName(),c.getVersion());
                    return false;
                }
            }
            else if (o instanceof UpdateElement) {
                UpdateElement u = (UpdateElement)o;
                if (Constants.IntStatusProcessed.equals(u.getInternalStatus())) {
                    Log.update.debug(
                        Fmt.S("rbUninstallRestore: Rolling back restored update %s",
                              u.getUpdateName()));

                    Map h = u.getComponents();
                    Iterator k = h.keySet().iterator();
                    while (k.hasNext()) {
                        String compName = (String)k.next();
                        c = (ComponentElement)h.get(compName);
                        if (c.getNextActive()!=null) {
                            print(
                                Fmt.S("rbUninstallRestore: Rolling back restored component"+
                                      " %s %s in update %s.",
                                      c.getNextActive().getComponentName(),
                                      c.getNextActive().getVersion(),
                                      u.getUpdateName()));
                        }

                        if (!rbUninstallRestoreComponent(c)) {
                            Log.update.warning(6846,c.getComponentName(),c.getVersion());
                            return false;
                        }
                    }
                }
                    //set tthe status to backedup (ie because the backedup components now
                    //need too be restored)
                c.setInternalStatus(Constants.IntStatusBackedup);
            }
        }
        /*
            Copy back the product.csv if it was backed up
        */
        if (removeComponent) {
            if (!restoreProductCSV(currentUpdateName)) {
                return false;
            }
        }
        return true;
    }


    /**
        This method tries to roll back all the files of the component that has been
        backed up.
    */
    private boolean rbUninstallBackupComponent (ComponentElement c,
                                                String currentUpdateName)
    {

        if (Constants.IntStatusProcessed.equals(c.getInternalStatus())) {

            String backupLocation = Fmt.S(Constants.ComponentBackUp,path,
                                          c.getComponentName(),c.getVersion());
            Log.update.debug("uninstallRollbackComponent :Rolling back files for"+
                             " uninstalled component %s %s from backup %s.",
                             c.getComponentName(),
                             c.getVersion(),
                             backupLocation);
            if (!restoreFiles(c,backupLocation,path,false)) {
                Log.update.warning(6848,c.getComponentName(),c.getVersion());
                return false;
            }

                //Bring back the BOM file for rolled back uninstalled component from the backup location
            String bomBackUpPath = Fmt.S(Constants.UpdateBackUp,path,currentUpdateName);
            bomBackUpPath = StringUtil.strcat(bomBackUpPath,c.getComponentName(),".bom");
            File tempBackup = new File(bomBackUpPath);
            String pathToBOMFile = StringUtil.strcat(path,
                                                     Fmt.S(Constants.BOMLocation,c.getComponentName()));
            File tempCurrent = new File(pathToBOMFile);
            Log.update.debug("uninstallRollbackComponent :Bringing back the BOM"+
                             " file for component %s", c.getComponentName());
            if (!IOUtil.copyFile(tempBackup,tempCurrent)) {
                Log.update.warning(6806,bomBackUpPath,pathToBOMFile);
                return false;
            }
            c.setInternalStatus(Constants.IntStatusNotProcessed);
        }
        return true;
    }

    /**
        This method tries to roll back all the files of the component that has been
        restored.
    */
    private boolean rbUninstallRestoreComponent (ComponentElement c)
    {

        if (Constants.IntStatusProcessed.equals(c.getInternalStatus())) {
            ComponentElement nextActive = c.getNextActive();
            String nextActiveVersion = null;
            if (nextActive!=null) {
                if (Constants.IntStatusProcessed.equals(nextActive.getInternalStatus())) {
                    nextActiveVersion = nextActive.getVersion();
                    print(
                        Fmt.S("rbUninstallRestoreComponent :Deleting file for restorerd"+
                              " component %s %s.",
                              nextActive.getComponentName(),nextActiveVersion));
                    if (!deleteFiles(c,path)) {
                        Log.update.warning(6814,nextActive.getComponentName(),
                                           nextActive.getVersion());
                        return false;
                    }
                    nextActive.setInternalStatus(Constants.IntStatusNotProcessed);
                }
            }
        }
        return true;
    }

    /**
        Deletes all files in an ComponentElement only if their status
        is processed and if they exist.
        The root directcory for the files in ComponentElement is dir1.
    */
    private boolean deleteFiles (ComponentElement c, String dir1)
    {
        String currentLocationTemplate = StringUtil.strcat(dir1,Constants.fs,"%s");
        Log.update.debug(
            Fmt.S("deleteFiles: Deleting files for component %s %s starting at location %s",
                  c.getComponentName(),c.getVersion(),dir1));

        List v = c.getFiles();
        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            String currentLocation = Fmt.S(currentLocationTemplate,fl.getPath());
            File temp = new File(currentLocation);
            if (temp.exists()) {
                    //delete file
                if (!temp.delete()) {
                    Log.update.warning(6805,currentLocation);
                    return false;
                }
            }
            else {
                Log.update.debug("deleteFiles: File %s does not exist for "+
                                 "component %s %s. Will not be deleted.",currentLocation,
                                 c.getComponentName(), c.getVersion());
            }
            fl.setInternalStatus(Constants.IntStatusNotProcessed);
        }
        return true;
    }

    /**
        Returns 1 if the value of the status attribute is "installed"
        Returns 2 otherwise
        returns 0 if an error ooccurs while processing
    */
    private int isUpdateAlreadyInstalled (String fileName)
    {
        Log.update.debug(
            Fmt.S("isUpdateAlreadyInstalled: Checking if update is already applied"+
                  " in %s.", fileName));
        File f = new File(fileName);
        if (!f.exists()) {
                //implies this update has not been installed.
            Log.update.debug(
                Fmt.S("isUpdateAlreadyInstalled: File %s is not present."+
                      " This means update has not yet been applied.", fileName));
            return 2;
        }
        Element rootElement = Util.readXMLFile(fileName,true);
        if (rootElement == null) {
                //could not load UM file
            Log.update.warning(6758,fileName);
            return 0;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.UpdateTag);
        if ((nl==null) || (nl.getLength()!=1)) {
            Log.update.warning(6786,Constants.UpdateTag,
                               fileName);
            return 0;
        }
        Element update = (Element)nl.item(0);
        String statusValue = update.getAttribute(Constants.StatusAttr);
        if (statusValue==null) {
            Log.update.warning(6787,Constants.StatusAttr,
                               Constants.UpdateTag,fileName);
            return 0;
        }
        else if (Constants.UpdateStatusInstalled.equals(statusValue)) {
            Log.update.debug(
                Fmt.S("isUpdateAlreadyInstalled: update in file %s is installed.",
                      fileName));
            return 1;
        }
        Log.update.debug(
            Fmt.S("isUpdateAlreadyInstalled: update in file %s is noot installed.",
                  fileName));
        return 2;
    }

    /**
        Reads from fileName, modifies and then saves to targetFileName.
        They can be the same files.
    */

    private boolean updateManifestFile (String fileName, String targetFileName,
                                        String statusValue)
    {
        Log.update.debug("updateManifestFile: Copying %s to %s and then "+
                         "setting status of <update> to %s",
                         fileName, targetFileName, statusValue);
        Element rootElement = Util.readXMLFile(fileName,true);
        if (rootElement == null) {
                //could not load UM file
            Log.update.warning(6758,fileName);
            return false;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.UpdateTag);
        if ((nl==null) || (nl.getLength()!=1)) {
            Log.update.warning(6786,Constants.UpdateTag,
                               fileName);
            return false;
        }
        Element update = (Element)nl.item(0);
        Date dd = new Date();
        update.setAttribute(Constants.TimeAttr,dd.toString());
        update.setAttribute(Constants.StatusAttr,statusValue);

            //make the file writable
        Log.update.debug("Making file %s writable.", targetFileName);
        if (!Util.makeFileWriteAble(targetFileName)) {
            Log.update.warning(6841,
                               Fmt.S("Could not make file %s writable",targetFileName));
            return false;
        }
        if (!saveXMLToFile(targetFileName,update)) {
            Log.update.warning(6808,targetFileName);
            return false;
        }

            //make file read only
        File f = new File(targetFileName);
        f.setReadOnly();

        return true;
    }

    /**
        This method updates the BOM file. The  status attribute of the <component>
        with version=acVersion is set to "active" and the status attribute
        of the <component> with version=scVersion is set to superceded.
        If either is null then no updates are done. However if they are not null
        and they are not found in the BOM then false is returned. Else true.

        If deletedFlag is true then the state status of the <component>
        with version=scVersion is set to deleted.
    */

    /*
        private boolean updateBOM (String compName, String scVersion,
        String acVersion)
        {
        return updateBOM(compName,scVersion,acVersion,false);
        }
    */

    private boolean updateBOM (String compName, String scVersion,
                               String acVersion)
    {
        Log.update.debug(
            Fmt.S("updateBOM: Updating %s. Setting version %s to superceded."+
                  " Setting %s to acrive.",compName,scVersion,acVersion));
        boolean ac=false,sc=false;
        if ((scVersion ==null) && (acVersion==null)) {
            Log.update.debug(
                Fmt.S("updateBOM: No versions to update for component %s.",
                      compName));
            return true;
        }

        String pathToBOMFile = StringUtil.strcat(path,
                                                 Fmt.S(Constants.BOMLocation,compName));
        Element rootElement = Util.readXMLFile(pathToBOMFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.warning(6766,pathToBOMFile);
            return false;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()==0)) {
            Log.update.warning(6767,Constants.ComponentTag,Constants.BomTag,
                               pathToBOMFile);
            return false;
        }
            //Go thru all the <component> and look for the required one
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String version = component.getAttribute(Constants.VersionAttr);
            if ((scVersion!=null) && (!sc)) {
                if (scVersion.equals(version)) {
                    Log.update.debug("updateBOM: Setting %s to superceded for"+
                                     "component %s.", scVersion, compName);
                    /*if (deletedFlag) {
                        component.setAttribute(Constants.StatusAttr,
                        Constants.StatusAttrDeleted);
                        }
                        else {*/
                    component.setAttribute(Constants.StatusAttr,
                                           Constants.StatusAttrSuperceeded);
                        //update the date
                    component.setAttribute(Constants.TimeAttr,
                                           getTime());
                        //}
                        //processed
                    sc = true;
                }
            }
            if ((acVersion!=null) && (!ac)) {
                if (acVersion.equals(version)) {
                    Log.update.debug("updateBOM: Setting %s to active for"+
                                     "component %s.", acVersion, compName);
                    component.setAttribute(Constants.StatusAttr,
                                           Constants.StatusAttrActive);
                    component.setAttribute(Constants.TimeAttr,
                                           getTime());
                    ac = true;
                }
            }
            if (sc && ac) {
                break;
            }
        }
        if (!saveXMLToFile(pathToBOMFile,rootElement)) {
            Log.update.warning(6808,pathToBOMFile);
            return false;
        }
        return true;
    }

    /**
        Returns the time from epoch as a hex string
    */
    private String getTime ()
    {
        d = new Date();
        long l = d.getTime();
        return Long.toHexString(l);
    }

    /**
        Converts a hex value to long.
        reurn -1 if error
    */
    private long convertToLong (String hex)
    {
        try {
            return Long.parseLong(hex, 16);
        }
        catch (NumberFormatException nfe) {
            Log.update.debug("Could not convert %s to a long  value", hex);
            return -1;
        }
    }

    /**
        Saves XML Data to file
    */

    private boolean saveXMLToFile (String fileName, Element element)
    {
        Log.update.debug("saveXMLToFile: Updating XML fiile %s",
                         fileName);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(new File(fileName));
            XMLUtil.serializeDocument(element.getOwnerDocument(), fos);
        }
        catch (FileNotFoundException fnfe) {
            Log.update.debug(
                Fmt.S("saveXMLToFile: Error updating BOM %s",
                      fnfe.getMessage()));
            return false;
        }
        catch (XMLParseException xpe) {
            Log.update.debug(
                Fmt.S("saveXMLToFile: Error updating BOM %s",
                      xpe.getMessage()));
            return false;
        }
        finally {
            IOUtil.close(fos);
        }
        return true;
    }

    /**
        This method copies all the files in ComponentElement from dir1 to dir2.
        If the files don't exist in dir1 then it will try to look up the
        file in depot. if it does not exist in depot then it is an error.

        Files are restored only if their status is "Processed" or
        previousVersion is true.

        This method is called 4 times. In 2 cases it is called to bring
        back files from the backup area when the installation failed.
        In this case previousVersion=false and only those files that were
        backed up will be restored (for files backedup fl.getInternalStatus=
        "Processed"
        In 2 cases this method is called when a component is uninstalled
        and the previous active version needs to be brought back OR component
        is being installed for the first time. In this case
        previousVersion=true
    */
    private boolean restoreFiles (ComponentElement c, String dir1, String dir2,
                                  boolean previousVersion)
    {
        String currentLocationTemplate = StringUtil.strcat(dir2,Constants.fs,"%s");

        List v = c.getFiles();
            //get the active version of this component in the BOM of the base image if any
        String baseImageVersion = getVesrionInBaseImageBOM(c.getComponentName());

        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            if (Constants.IntStatusProcessed.equals(fl.getInternalStatus())||
                previousVersion) {
                String currentLocation = Fmt.S(currentLocationTemplate,fl.getPath());

                String pathToFile =
                    getFileLocation(dir1,c.getComponentName(),
                                    c.getVersion(),
                                    fl.getPath(),
                                    baseImageVersion);
                if (pathToFile== null) {
                    Log.update.warning(6862,fl.getPath());
                    return false;
                }
                else {
                    if (pathToFile.startsWith(path)) {
                        Log.update.info(6841,
                                        Fmt.S("Copying customized file for component "+
                                              "%s %s from %s to %s", c.getComponentName(),c.getVersion(),
                                              pathToFile,currentLocation));
                    }
                }
                if (!IOUtil.copyFile(new File(pathToFile), new File(currentLocation))) {
                    Log.update.warning(6806,pathToFile,currentLocation);
                    return false;
                }

                if (previousVersion) {
                    fl.setInternalStatus(Constants.IntStatusProcessed);
                }
                else {
                    fl.setInternalStatus(Constants.IntStatusNotProcessed);
                }
            }
        }
        return true;
    }

    /**
     * Creates a parent directory for the destination file if necessary before the actual copy
     * @param sourceFile The source file
     * @param destFile The destination file
     * @return true if copy is successful.
     */
    private boolean copyFileWithParentDir (File sourceFile, File destFile)
    {

            //Strip the filename from fl.getPath() and create the backup directory
            //componentBackupDirectory+ (fl.getPath - fileName)
        File temp = new File(destFile.getPath());
        String parentDir = temp.getParent();
        if (parentDir!=null) {
            if (!createBackUpDirectory(parentDir)) {
                return false;
            }
        }

        if (!IOUtil.copyFile(sourceFile, destFile)) {
            Log.update.warning(6806,sourceFile,destFile);
            return false;
        }

        return true;
    }
    /**
        This method copies all the files in ComponentElement from dir1 to dir2.
        if existflag is true and file does not exist the method returns
        with false.
        if deleteFlag is true then an attempt is made to delete the file.
        If deletion cannot be mde then false is returned.

        If file exists in the destination location an attempt will be made
        to delete t he file before it gets copied over from the source.

        If the boolean onlyIfSizeDiffers is true then files are copied over
        only if the file size specified by the size attribute is different
        from the file size at the source.
    */
    private boolean copyFiles (ComponentElement c, String dir1, String dir2,
                               boolean existFlag, boolean deleteFlag,
                               boolean onlyIfSizeDiffers)
    {

        String currentLocationTemplate = StringUtil.strcat(dir1,Constants.fs,"%s");
        String backupLocationTemplate = StringUtil.strcat(dir2,Constants.fs,"%s");


        List v = c.getFiles();
        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            String backupLocation = Fmt.S(backupLocationTemplate,fl.getPath());
            String currentLocation = Fmt.S(currentLocationTemplate,fl.getPath());
            File temp = new File(currentLocation);
            if (!temp.exists()) {
                    //this message needs special handling for Buyer.
                if (needToPrintWarning(fl.getPath())) {
                        //also check if this is a OS specific component. In that
                        //case there is no need to print the message since the file
                        //will be missing
                    String osInExclude = (String)excludeBOM.get(c.getComponentName());
                    if ((osInExclude != null) &&
                        !osName.equals(osInExclude)) {

                        continue;
                    }
                    Log.update.info(6788,currentLocation);
                }
                if (existFlag) {
                    return false;
                }
                    //process next file cause you cant copy it
                continue;
            }
            File tempBackup = new File(backupLocation);
            if (tempBackup.exists()) {
                    //try to delete it
                    //if not able to delete file, hopefully overwrite from source will
                    //go thru. if that fails then there will be an error.
                tempBackup.delete();

            }
            if (onlyIfSizeDiffers) {
                if (!verifyFileSizeAndHash(currentLocation,fl.getMD5(),fl.getSize())) {
                    Log.update.debug(
                        Fmt.S("Backing up files for component %s %s from %s to %s",
                              c.getComponentName(),c.getVersion(),dir1,dir2));
                    if (!copyFileWithParentDir(temp, tempBackup)) {
                        return false;
                    }
                }

            }
            else {
                if (!copyFileWithParentDir(temp, tempBackup)) {
                        return false;
                }
            }
            /*
                Set status to "Processd" even if you cant delete
                the file. Since its backed up it can be restored
                if required.
            */
            fl.setInternalStatus(Constants.IntStatusProcessed);
            if (deleteFlag) {
                /*
                    Delete the file in the current location.
                */
                if (!temp.delete()) {
                        //Could not delete file
                    Log.update.warning(6805,currentLocation);
                    return false;
                }
            }
        }
        return true;

    }
    /**
        This method checks if all the files listed for uninstall
        can be moved to a new location. i.e copy to a backup location
        and then delete. It just does a check. I know its kind of
        double work because you have to go thru the file list again
        but at least the recovery process will be easier if we fail
        midway while trying to copy a file and we don't have permissions.
        The backup directory structure will be created.
    */
    private boolean checkIfOkToUninstall (Map uninstallUpdateHash)
    {
        int errorCount =0;
        ComponentElement c = null;


        Iterator uuh = uninstallUpdateHash.keySet().iterator();
        while (uuh.hasNext()) {
            String  compUpKey = (String)uuh.next();
            Object o = uninstallUpdateHash.get(compUpKey);
            if (o instanceof ComponentElement) {
                c = (ComponentElement)o;
                print(
                    Fmt.S("Checking to see if component %s %s can"+
                          " be uninstalled",c.getComponentName(),c.getVersion()));
                if (!checkIfOkToUninstallComponent(c)) {
                    ++errorCount;
                }
            }
            else if (o instanceof UpdateElement) {
                UpdateElement u = (UpdateElement)o;
                print(
                    Fmt.S("Checking to see if the update %s can"+
                          " be uninstalled",u.getUpdateName()));
                Map h = u.getComponents();
                Iterator k = h.keySet().iterator();
                while (k.hasNext()) {
                    String compName = (String)k.next();
                    c = (ComponentElement)h.get(compName);
                    Log.update.debug(
                        Fmt.S("checkIfOkToUninstall: Checking to see if component %s %s in " +
                              "update %scan be uninstalled",
                              c.getComponentName(),
                              c.getVersion(),
                              u.getUpdateName()));
                    if (!checkIfOkToUninstallComponent(c)) {
                        ++errorCount;
                    }
                }
            }
        }
        if (errorCount!=0) {
            return false;
        }
        return true;
    }

    /**
        Method checks if a component is okay to be uninsatlled
    */
    private boolean checkIfOkToUninstallComponent (ComponentElement c)
    {
        int errorCount = 0;

        Log.update.debug(
            Fmt.S("checkIfOkToUninstallComponent: Checking to see if component %s %s can be uninstalled",
                  c.getComponentName(),c.getVersion()));
        if (!checkIfFilesAreWritable(c)) {
            ++errorCount;
        }
        /*
            Now once this component gets uninstalled the next active version
            will be restored from a previous backup or depot.
        */
        ComponentElement temp = c.getNextActive();
        if (temp!=null) {
            if (!checkIfFileExists(temp,
                                   Fmt.S(Constants.ComponentBackUp,path,temp.getComponentName(),
                                         temp.getVersion()))) {
                ++errorCount;
            }
                //Also create theh directory structure for the files being brought
                //in from the backup just in case the directory structure has been deleted.
            if (!createDirectoryStructure(temp,path)) {
                Log.update.debug(
                    Fmt.S("checkIfOkToUninstallComponent: A problem occurred while "+
                          "creating the directory structure for files in component %s %s.",
                          temp.getComponentName(),temp.getVersion()));
                ++errorCount;
            }

        }
        if (errorCount != 0) {
            return false;
        }
        return true;
    }

    /**
        Checks if the baseImageVersion matches the version
    */

    private boolean checkIfVersionsMatch (String baseImageVersion,
                                          String version,
                                          String compName)
    {
        if ((baseImageVersion == null) ||
            baseImageVersion.equals(Constants.NoActive))
        {
            Log.update.debug(
                Fmt.S("checkIfVersionsMatch: Couldn't find active version for %s in image BOM file.",
                      compName));
            return false;
        }
        if (Util.compareVersionNumbersNoWildCards(version, baseImageVersion) != 0) {
            Log.update.debug(
                Fmt.S("checkIfVersionsMatch: The version %s for %s does not match %s in the base image bom file.",
                      version,compName,baseImageVersion));
            return false;
        }
        return true;
    }

    /**
        Returns the active version of the component compName in the base image if any
    */
    private String getVesrionInBaseImageBOM (String compName)
    {
        String baseImageTemplate = Fmt.S(BaseImageDir,depotVersion,depotImageDir);
        if (!nowProcessing) {
            baseImageTemplate =
                StringUtil.strcat(baseImageTemplate,Constants.DocRoot,Constants.fs);
        }
        baseImageTemplate = StringUtil.strcat(baseImageTemplate,"%s");
        String pathToBomInImage = StringUtil.strcat(
            Fmt.S(baseImageTemplate,Constants.BOMLocationDir),
            compName,".bom");
        return (getActiveVersion(pathToBomInImage,compName,
                                 MapUtil.map()));
    }

    /**
        for a given component returuns the BOM location.
        if BOM is present in depot then it returns the depot location.
        else if BOM is present in the base image and the versions match then it returns
        the base images BOM location.
    */

    private String getBOMLocation (String compName, String compVersion,
                                   String baseImageVersion)
    {
        String baseImageTemplate = Fmt.S(BaseImageDir,depotVersion,depotImageDir);
        if (!nowProcessing) {
            baseImageTemplate =
                StringUtil.strcat(baseImageTemplate,Constants.DocRoot,Constants.fs);
        }
        baseImageTemplate = StringUtil.strcat(baseImageTemplate,"%s");
        String fName =
            Fmt.S(Constants.BomInDepot,compName,compVersion,compName);
        File f = new File(fName);
        if (!f.exists()) {
            String pathToBomInImage = StringUtil.strcat(
                Fmt.S(baseImageTemplate,Constants.BOMLocationDir),
                compName,".bom");
            if (!checkIfVersionsMatch(baseImageVersion,compVersion,compName)) {
                return null;
            }
            else {
                return pathToBomInImage;
            }
        }
        else {
            return fName;
        }

    }

    /**
        This method returns the location of a file given a particular dir, file name,
        component name, component version and component version in base image.
        It seraches in the following
        order and returns the location where its present. Returns null if it cant
        find it.
        1)<product_instance>/etc/backup/<component-name>/<component-version>/file
        2) <...>/depot/<product>/<component-name>/<component-version>/file
        3) <...>/depot/<product>/<depotversion>/<depotImageDir>/file
        In the last case the version of the component for the file should be the same
        as the version of the component in the base image
    */
    private String getFileLocation (String dir,String compName, String compVersion,
                                    String fileName, String baseImageVersion)
    {
        String backupLocationTemplate = StringUtil.strcat(dir,Constants.fs,"%s");
        String depotLocationTemplate =
            Fmt.S(ComponentInDepotDir,compName,compVersion);
        String baseImageTemplate = Fmt.S(BaseImageDir,depotVersion,depotImageDir);
        if (!nowProcessing) {
            depotLocationTemplate = StringUtil.strcat(depotLocationTemplate,
                                                      Constants.DocRoot,Constants.fs);
            baseImageTemplate =
                StringUtil.strcat(baseImageTemplate,Constants.DocRoot,Constants.fs);
        }
        depotLocationTemplate =
            StringUtil.strcat(depotLocationTemplate,"%s");
        baseImageTemplate = StringUtil.strcat(baseImageTemplate,"%s");

        String fileLoc = Fmt.S(backupLocationTemplate,fileName);
        File f = new File(fileLoc);
        if (!f.exists()) {
            fileLoc = Fmt.S(depotLocationTemplate,fileName);
            f = new File(fileLoc);
            if (!f.exists()) {
                fileLoc = Fmt.S(baseImageTemplate,fileName);
                f = new File(fileLoc);
                if (f.exists()) {

                    if (!checkIfVersionsMatch(baseImageVersion,compVersion,compName)) {
                        return null;
                    }
                    else {
                        return fileLoc;
                    }
                }
                else {
                    return null;
                }
            }
            else {
                return fileLoc;
            }
        }
        else {
            return fileLoc;
        }
    }

    /**
        Checks if a components files exists in the directory specified.
        If it doesn't exist then it checks if the file is present in
        the depot. if it does not exist in te depot then check in the
        depot image. before checking in the image make sure the version
        of the component in the image is the same as the version that
        you want.
    */
    private boolean checkIfFileExists (ComponentElement c, String dir)
    {
        Log.update.debug(
            Fmt.S("checkIfFileExists: checking if all files in component %s %s exists.",
                  c.getComponentName(),c.getVersion()));


            //get the active version of this component in the BOM of the base image if any
        String baseImageVersion = getVesrionInBaseImageBOM(c.getComponentName());
        int errorCount = 0;
        List v = c.getFiles();
        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            String pathToFile =
                getFileLocation(dir,c.getComponentName(),c.getVersion(),fl.getPath(),
                                baseImageVersion);
            if (pathToFile == null) {
                Log.update.warning(6862,fl.getPath());
                ++errorCount;
            }
        }
        if (errorCount != 0) {
            return false;
        }
        return true;
    }

    /**
        Given a ComponentElement this method checks if a zip/jar file
        is writable.
    */
    private boolean checkIfFilesAreWritable (ComponentElement c)
    {
        int errorCount = 0;
        ariba.util.core.Date date = new ariba.util.core.Date();

        List v = c.getFiles();
        for (int l=0;l<v.size();++l) {
            FileElement fl = (FileElement)v.get(l);
            String pathToFile = StringUtil.strcat(path,Constants.fs,
                                                  fl.getPath());
            File f = new File(pathToFile);
            if (!f.exists()) {
                    //A warning will be printed when the actual copy occurs during backup
                    //if needed (warning will not be printed while trying to backup feature component
                    //files that may not have been installed)
                continue;
            }

                //check if file name ends with .zip/.jar. convert file name to lowercase so that
                //you can take care of all combination of cases for "zip" and "jar"
            String fileNameLowerCase = pathToFile.toLowerCase();
            for (int i = 0; i<Constants.FilesThatCanBeLocked.length; ++i) {
                if (fileNameLowerCase.endsWith(Constants.FilesThatCanBeLocked[i])) {
                    String suffix = date.toFileTimeString();
                    String newFileName = StringUtil.strcat(pathToFile,".",suffix);
                    File newFile = new File(newFileName);
                    if (!f.renameTo(newFile)) {
                            //Couldn't rename. File is locked by some process. Print out a message and increment error count
                        Log.update.warning(6925,pathToFile);
                        ++errorCount;

                    }
                    else {
                            //now rename the file back to the original file
                        if (!newFile.renameTo(f)) {
                                //Print out a clear message and Return immediately. The message must ask the user to manually copy
                                //the file over.
                            Log.update.warning(6841,"***************************************************");
                            Log.update.warning(6841,
                                               Fmt.S("Please copy file \"%s\" to file \"%s\" and re-run UDT.",
                                                     newFileName, pathToFile));
                            Log.update.warning(6841,"***************************************************");
                            return false;
                        }
                    }
                        //file can end with only one kind of extension and its been processed. So break
                    break;
                }
            }
        }
        if (errorCount!=0) {
            return false;
        }
        return true;
    }

    /**
        Given a ComponentElement this metod goes thru the list of files
        and creates the appropriate directory structure in "dir" so that
        files from the component could be copied into dir.
    */
    private boolean createDirectoryStructure (ComponentElement c,String dir)
    {
        dir = StringUtil.strcat(dir,Constants.fs);
        Log.update.debug(
            Fmt.S("createDirectoryStructure: creates directory structure for files in "+
                  "component %s %s starting at %s",c.getComponentName(),c.getVersion(),
                  dir));

        List v = c.getFiles();
        for (int i=0;i<v.size();++i) {
            FileElement fl = (FileElement)v.get(i);

                //Strip the filename from fl.getPath() and create the backup directory
                //dir+ (fl.getPath - fileName)

            File temp = new File(fl.getPath());
            String parentDir = temp.getParent();
            if (parentDir!=null) {
                if (!createBackUpDirectory(StringUtil.strcat(
                                               dir,parentDir))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
        Returns true if the file is binary. Buyer/ACM/Analysis considers files
        ending with the following extension as binary files:
        .gif .zip .jar .exe .dll .xls .doc .ear .cab .class .pdf .bmp
        (got list from //ariba/tools/build/bin/strip-tags.pl)
    */
    private boolean isBinaryFile (String fileName)
    {
        int lastDot = fileName.lastIndexOf(".");
        String extension = fileName.substring(lastDot+1);
        extension = extension.toLowerCase();
        if (Constants.BinaryExtension.contains(extension)) {
            return true;
        }
        return false;
    }
    /**
        Given a fileName, the original hash and the original size this method
        computes the current hash and size and compares it against the original.
        If the OS is Unix and the comparison fails false is returned.


        If the comparison fails and the file is not binary and the platform
        is Windows, an attempt is made to strip ctrl-m's from the file and recalculate
        hash and size and re-compare
        Returns true if the comparison fails.
    */
    private boolean verifyFileSizeAndHash (String fileName,
                                           String hash, long size)
    {
        Map computedValues = MapUtil.map();
        if (!realVerifyFileSizeAndHash(fileName,hash,size,computedValues)) {
            if (!isBinaryFile(fileName)) {

                    //try deleting the tempfile if it exists
                File tempFile = new File(Constants.TempFile);
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                InputStream in = null;
                OutputStream out = null;
                try {
                    in  = new FileInputStream(fileName);
                    out = new FileOutputStream(Constants.TempFile);
                }
                catch (IOException ioe) {
                    Log.update.debug("Could not initialize files for ctrl-m modification");
                    return false;
                }
                try {
                    if (ServerUtil.isWin32) {
                        inputStreamToOutputStreamRemovingByte(in,
                                                              out,
                                                              new byte[Constants.BufferSize],
                                                              Constants.CR);
                    }
                    else {
                            //Unix
                        inputStreamToOutputStreamAddingByte(in,
                                                            out,
                                                            new byte[Constants.BufferSize],
                                                            Constants.CR);
                    }
                }
                catch (IOException ioe) {
                    Log.update.debug(
                        Fmt.S("An exception occurred while doing ctrl-m modifications"+
                              " in file %s : %s",fileName,ioe.getMessage()));
                    return false;
                }
                finally {
                    IOUtil.close(out);
                    IOUtil.close(in);
                }
                if (!realVerifyFileSizeAndHash(Constants.TempFile,hash,size,
                        computedValues)) {
                    Log.update.debug("The original hash %s or size %s does " +
                            " not match with the" +
                            " computed hash %s or size %s for file %s",
                            hash, Long.toString(size),
                            computedValues.get(Constants.MD5Attr),
                            computedValues.get(Constants.SizeAttr),
                            fileName);
                    return false;
                }
                tempFile.delete();
                return true;
            }
            else {
                    //not a binary file. so the comparison has failed.
                Log.update.debug("The original hash %s or size %s does " +
                            " not match with the" +
                            " computed hash %s or size %s for file %s",
                            hash, Long.toString(size),
                            computedValues.get(Constants.MD5Attr),
                            computedValues.get(Constants.SizeAttr),
                            fileName);


                return false;
            }
        }
        return true;

    }
    /**
        Given a fileName, the original hash and the original size this method
        computes the current hash and size and compares it against the original.
        It also returns the computed size and md5 hash in a Map
        Returns true if the comparison fails or if an error occurs
    */

    private boolean realVerifyFileSizeAndHash (String fileName,
                                               String hash,
                                               long size,
                                               Map computedValues)
    {
            //to avoid npe's if any
        computedValues.put(Constants.MD5Attr,ariba.util.core.Constants.EmptyString);
        computedValues.put(Constants.SizeAttr,ariba.util.core.Constants.EmptyString);
        int errorCount =0;
        try {
            if (md ==  null) {
                md = MessageDigest.getInstance("MD5");
            }
            md.reset();
        }
        catch (NoSuchAlgorithmException nsa) {
            Log.update.debug("exception while creating message digest : %s",
                             nsa.getMessage());
            return false;
        }


            //read the file

        File f = new File(fileName);

        URL url = URLUtil.url(f);
        if (url == null) {
            Log.update.debug("verifyFileSizeAndHash: Could not create URL for file name %s", fileName);
            return false;
        }
            //Comment out the hash for the time being

        byte[] fileContents = IOUtil.bytesFromURL(url);
        if (fileContents == null ) {
            Log.update.debug("Could not read byte for file name %s", fileName);
            return false;
        }
        md.update(fileContents);
        byte[] encode = md.digest();

        String stringDigest = toHex(encode);
        if (!hash.equalsIgnoreCase(stringDigest)) {
            computedValues.put(Constants.MD5Attr,stringDigest);
            ++errorCount;
        }


        long currentSize = f.length();

        if (currentSize!=size) {
            computedValues.put(Constants.SizeAttr,Long.toString(size));
            ++errorCount;
        }

        if (errorCount != 0) {
            return false;
        }

        return true;
    }


    /**
        Returns true if it can create a backup directory successfully
    */
    private boolean createBackUpDirectory (String backupLoc)
    {
            //try creating the backup location
            //xxx: somtimes it would be better to delete the directory if it exists.
        File backUpDir = new File(backupLoc);
        if (!backUpDir.exists()) {
            if (!backUpDir.mkdirs()) {
                Log.update.warning(6790,backupLoc);
                return false;
            }
        }
        else {
            /*
                If this is a Windows System then check if the path specified
                by backuupLoc matches the directory path on the disk
                WITTHOUT ignoring case. If it does not match
                then print out a warning saying you have two directories
                with same name but different case.
            */
            if (ServerUtil.isWin32) {
                try {
                        //get case sensitiv path on disk
                    String canPath = backUpDir.getCanonicalPath();

                        //now backupLoc might have a "/" in the end and canPath might not. So
                        //just add a "/" to both before compressing.
                    backupLoc = StringUtil.strcat(backupLoc,"/");
                    canPath = StringUtil.strcat(canPath,"/");

                    backupLoc = Util.compressSlashesWithSingleForwardSlash(backupLoc);
                    canPath = Util.compressSlashesWithSingleForwardSlash(canPath);

                    String temp = backupLoc;
                    String temp1 = canPath;

                        //strip the path to the instance from the above paths before comparison
                    int pathLength = path.length();
                    backupLoc = backupLoc.substring(pathLength);
                    canPath = canPath.substring(pathLength);

                    if (!backupLoc.equals(canPath)) {
                        /*
                            xxx: this is a hack
                            Now check in this the 3rdParty/perl5 issue that we have
                            in our distribution. If it is then don't display the
                            message :)
                        */
                        if (backupLoc.indexOf("3rdParty/perl5/")!=-1) {
                            Log.update.debug("Case issue %s", temp);
                        }
                        else {
                            Log.update.info(6919,temp,temp1);
                        }
                    }
                }
                catch (IOException ioe) {
                    Log.update.info(6841,Fmt.S("An exception occurred when checking " +
                                               "case issues for directory %s",backupLoc));
                    return false;
                }
            }
        }
        return true;
    }

    /**
        A wrapper for the below method
    */
    /**
        Given a component name and a version number this method reads the equivalent
        entry in the BOM file and returns a Component data structure.
        Returns null if there is an error.

        If flag is true then this method also initializes the component that will
        be active once the current component gets uninstalled. This is stored in
        nextActive = currentcomponent.getNextActive

        This method is called recursively
    */
    private ComponentElement getComponentInBOM (String pathToBOMFile,
                                                String componentName,
                                                String componentVersion,
                                                boolean flag)
    {
        ComponentElement componentElement = null;

        Element rootElement = Util.readXMLFile(pathToBOMFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.warning(6766,pathToBOMFile);
            return null;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()==0)) {
            Log.update.warning(6767,Constants.ComponentTag,Constants.BomTag,
                               pathToBOMFile);
            return null;
        }
            //Go thru all the <component> and look for the required one
        boolean foundComponent = false;
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String status = component.getAttribute(Constants.StatusAttr);
            String version = component.getAttribute(Constants.VersionAttr);
            if (Util.compareVersionNumbersNoWildCards(version, componentVersion) == 0) {
                    //great we have a match
                componentElement = new ComponentElement (
                    component.getAttribute(Constants.NameAttr),
                    version,
                    status,
                    component.getAttribute(Constants.TimeAttr),
                    component.getAttribute(Constants.DescriptionAttr));

                    //Also initalize the files list
                if (!initalizeFilesInComponent(componentElement,component,
                                               pathToBOMFile)) {
                    return null;
                }
                    //Initialize depedencies for this component
                if (!initalizeDependenciesInComponent(componentElement,component,
                                                      pathToBOMFile)) {
                    return null;
                }
                foundComponent = true;
                break;
            }
        }

        if (!foundComponent) {
            Log.update.info(6849,componentName,componentVersion,pathToBOMFile);
            return null;
        }

        if (flag) {
            Map temp = MapUtil.map();
            temp.put(componentName,componentVersion);
            String nextActiveVersion = getActiveVersion(pathToBOMFile,componentName,
                                                        temp);
            if (nextActiveVersion == null) {
                Log.update.warning(6772,componentName,componentVersion,pathToBOMFile);
                return null;
            }
            if (!nextActiveVersion.equals(Constants.NoActive)) {
                ComponentElement nextActive = getComponentInBOM(pathToBOMFile,
                                                                componentName,nextActiveVersion,false);
                if (nextActive==null) {
                    Log.update.warning(6809,componentName,componentVersion,pathToBOMFile);
                    return null;
                }
                componentElement.setNextActive(nextActive);
            }
        }
        return componentElement;

    }

    /**
        Given a ComponentElement and a Element <component>, this method initializes the
        dependencies vector in the ComponentElement.
        The pathToBOMFile contains the file from which this <component> was loaded
    */
    private boolean initalizeDependenciesInComponent (ComponentElement c,
                                                      Element component,
                                                      String pathToBOMFile)
    {
        Log.update.debug("initalizeDependenciesInComponent: Processing dependeencies for component"+
                         " %s %s in file %s.",
                         c.getComponentName(), c.getVersion(), pathToBOMFile);
        NodeList fl = Util.getNodeList(component, Constants.DependencyTag);
        if (fl==null) {
            Log.update.warning(6767,Constants.DependencyTag,Constants.ComponentTag,
                               pathToBOMFile);
            return false;
        }
        List dependencyVector = c.getDependencies();

        for (int j=0; j< fl.getLength();++j) {
            Element dependency = (Element)fl.item(j);
            String compName = dependency.getAttribute(Constants.NameAttr);
            if ((compName != null)&&(!compName.equals(Constants.None))) {
                dependencyVector.add(compName);
            }
        }
        return true;
    }

    /**
        Given a ComponentElement and a Element <component>, this method initializes the
        files vector in the ComponentElement.
        The pathToBOMFile contains the file from which this <component> was loaded
    */
    private boolean initalizeFilesInComponent (ComponentElement c, Element component,
                                               String pathToBOMFile)
    {
        Log.update.debug("initalizeFilesInComponent: Processing files for coomponent"+
                         " %s %s in file %s.",
                         c.getComponentName(), c.getVersion(), pathToBOMFile);
        NodeList fl = Util.getNodeList(component, Constants.FileTag);
        if ((fl==null) || (fl.getLength()==0)) {
            Log.update.warning(6767,
                               Constants.FileTag,
                               Constants.ComponentTag,
                               pathToBOMFile);
            return false;
        }
        List fileVector = c.getFiles();

        for (int j=0; j< fl.getLength();++j) {
            Element file = (Element)fl.item(j);
            String pathInFile = file.getAttribute(Constants.PathAttr);
            if (pathInFile != null) {
                String osToExclude = (String)excludeFile.get(pathInFile);
                    //check if the parent directory of the file is listed.
                if (osToExclude==null) {
                    File tempFile = new File(pathInFile);
                    String tempFileName = tempFile.getParent();
                        //replace any "\" with "/" since we always use "/" in ExcludeFile.csv.
                        //This is because java returns patform specific file separators
                        //there may be no parent directory
                    if (tempFileName!=null) {
                        tempFileName = Util.compressSlashesWithSingleForwardSlash(tempFileName);
                        osToExclude = (String)excludeFile.get(tempFileName);
                    }
                }

                    //Exclude file if this is a special file and
                    //the OS does not match the current oS
                if ((osToExclude!=null) && (!osName.equals(osToExclude))) {
                    Log.update.debug(
                        Fmt.S("Excluding file %s from component %s",
                              pathInFile,c.getComponentName()));
                    continue;
                }
                if (nowProcessing && (CommonKeys.buyer.equalsIgnoreCase(product) ||
                    CommonKeys.sourcing.equalsIgnoreCase(product))) {
                    if (pathInFile.startsWith(Constants.DocRoot)) {
                        continue;
                    }
                }
                else if (nowProcessing && (CommonKeys.acm.equalsIgnoreCase(product) ||
                    CommonKeys.analysis.equalsIgnoreCase(product))) {
                    // We install all files because ACM and Analysis servers
                    // have the docroot directory.
                }
                else {
                    if (!pathInFile.startsWith(Constants.DocRoot)) {
                        continue;
                    }
                    else {
                            //strip "docroot" and the file separator from the path
                        pathInFile =
                            pathInFile.substring(Constants.DocRoot.length()+1);
                    }
                }
                    //Convert size to long
                long origSize = 0;
                try {
                    origSize =
                        new Long(
                            file.getAttribute(Constants.SizeAttr)).longValue();
                }
                catch (NumberFormatException nfe) {
                    Log.update.debug(
                        Fmt.S("initalizeFilesInComponent: NumberFormatException for " +
                            "file %s : %s",pathInFile,
                              nfe.getMessage()));
                    return false;
                }
                FileElement fileElement = new FileElement(
                    pathInFile,
                    origSize,
                    file.getAttribute(Constants.TimeAttr),
                    file.getAttribute(Constants.MD5Attr),
                    file.getAttribute(Constants.ActionAttr));
                fileVector.add(fileElement);
            }
            else {
                    //path cant be null. better return an error now
                    //rather than later
                Log.update.warning(6787,
                                   Constants.PathAttr,
                                   Constants.FileTag,
                                   pathToBOMFile);
                return false;
            }
        }
        return true;
    }
    /**
        This method checks to see if the base version of the component
        is present in the system, if the component is being updated with
        a delivery type of "diff". Heres the algo

        for each component in updateHash {
        check if this is a new component (BOM file should not exist on the instance)
        if (yes) {
        continue;
        }
        else {
        check if this component has a delivery type=diff in the update
        }
        if (no) {
        continue;
        }
        else {
        check if the base version is present in the instance.
        }
        if (no) {
        increment error count;
        log message;
        continue;
        }
        }
    */

    private  boolean checkSelfDependency (Map updateHash,
                                          Map uninstallHash)
    {
        int errorCount =0;
        Iterator e = updateHash.keySet().iterator();
        Log.update.debug("Processing self dependency for components whose delivery is of type diff");
        while (e.hasNext()) {
            String compName = (String)e.next();
            String version = (String)updateHash.get(compName);
            Log.update.debug(
                Fmt.S("checkSelfDependency: Checking self dependency for %s",compName));
            String pathToBOMFile = StringUtil.strcat(path,
                                                     Fmt.S(Constants.BOMLocation,compName));
            if (!(new File(pathToBOMFile)).exists()) {
                Log.update.debug("checkSelfDependency: New component. No Self Dependency : "+ compName);
                continue;
            }
            /*
                The bom for any component thah came with the update will
                be in the depot at either in tthe components or in tthe image
            */

            String baseImageVersion =
                getVesrionInBaseImageBOM(compName);
            String pathToUpdateBOMFile =
                getBOMLocation(compName,version,baseImageVersion);
            String baseVersion = getBaseVersion (pathToUpdateBOMFile);
            if (baseVersion == null) {
                ++errorCount;
                continue;
            }
            else if (baseVersion.equals(Constants.NoBase)) {
                    //no base atribute. which is fine.
                Log.update.debug(
                    Fmt.S("checkSelfDependency: The delivery type is whole for the "+
                          "component %s.",compName));
                continue;
            }
                //lets check if the base version will be present in the instance.
            String activeVer = getActiveVersion(pathToBOMFile,compName,uninstallHash);
            if ((activeVer == null) || (activeVer.equals(Constants.NoActive))) {
                ++errorCount;
                Log.update.debug(
                    Fmt.S("checkSelfDependency: In order to install component %s the "+
                          " base vesrion %s must exist.",compName,baseVersion));
                Log.update.warning(6772,compName,version,pathToBOMFile);
                continue;
            }
            if (Util.compareVersionNumbersNoWildCards(activeVer, baseVersion)!=0) {
                ++errorCount;
                Log.update.warning(6765,compName,baseVersion,activeVer);
                continue;
            }
        }
        if (errorCount != 0) {
                //print an error message saying how many self dependency errors there were
            return false;
        }
        return true;
    }

    /**
        Given a <bom> this method returns the base version present in the
        "base" attribute of the delivery tag.
        if there is an error in processing this method return null.
        if "base" is not present then it returns "nobase"
        else it returns the value of "base"
    */
    private String getBaseVersion (String pathToBOMFile)
    {

        Log.update.debug("getBaseVersion; Getting tthe base version in file %s",
                         pathToBOMFile);
        Element rootElement = Util.readXMLFile(pathToBOMFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.warning(6766,pathToBOMFile);
            return null;
        }
        NodeList nl = Util.getNodeList(rootElement,Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()!=1)) {
            Log.update.warning(6786,Constants.ComponentTag,pathToBOMFile);
            return null;
        }
        Element component = (Element)nl.item(0);
        NodeList deliveryList = Util.getNodeList(component,Constants.DeliveryTag);
        if ((deliveryList == null) || (deliveryList.getLength()!=1)) {
            Log.update.warning(6786,Constants.DeliveryTag,pathToBOMFile);
            return null;
        }
        Element deliveryElement = (Element)deliveryList.item(0);
        String typeValue = deliveryElement.getAttribute(Constants.TypeAttr);
        if (StringUtil.nullOrEmptyOrBlankString(typeValue)) {
            Log.update.warning(6787,Constants.TypeAttr,Constants.DeliveryTag,
                               pathToBOMFile);
            return null;
        }
        if (Constants.TypeAttrWhole.equals(typeValue)) {
            return Constants.NoBase;
        }
        else if (Constants.TypeAttrDiff.equals(typeValue)) {
            String baseValue = deliveryElement.getAttribute(Constants.BaseAttr);
            if (StringUtil.nullOrEmptyOrBlankString(baseValue)) {
                Log.update.warning(6787,Constants.BaseAttr,Constants.DeliveryTag,
                                   pathToBOMFile);
                return null;
            }
            return baseValue;
        }
        else {
            Log.update.warning(6787,Constants.TypeAttr,Constants.DeliveryTag,
                               pathToBOMFile);
            return null;
        }
    }
    /**
        This method checks if the dependent components version will be present in the
        system once the update goes through. The following algorithm is used:
        {
        for each component(X) in dependency hash {
        if (X is presnt in updateHash (its being updated as part of this update)) {
        if (X's version falls within the new version) {
        continue; (assuming we will not install and uninstall a component
        with the same version in the same update :))
        }
        else {
        log warning;
        increment error count;
        continue;
        }
        Load BOM for X
        if (load fails) {
        log warning;
        increment error count;
        continue;
        }
        Determine the version that will be active "VerNum"(in case its also being
        uninstalled, else it will be the version with status=active)
        if (VerNum does not satisfy the range ofo X's version) {
        log warning;
        increment error count;
        }
        if (error count !+ zero) {
        log error
        return false
        }
        return true;
        }
    */
    private boolean checkDependency (Map dependencyHash, Map updateHash,
                                     Map uninstallHash, String BOMFileLocation)
    {
        int errorCount = 0;
        Iterator e = dependencyHash.keySet().iterator();
        while (e.hasNext()) {
            String key = (String)e.next();
            String depVersion = (String)dependencyHash.get(key);
            StringTokenizer st = new StringTokenizer(key,"|");
            String depComponent = null;
            try {
                depComponent = st.nextToken();
            }
            catch (NoSuchElementException nse) {
                depComponent = null;
            }
            if (depComponent == null) {
                    //should be warning
                Log.update.warning(6764,key);
                ++errorCount;
                continue;
            }
            Log.update.debug("checkDependency: Doing dependency check for component %s %s",
                             depComponent, depVersion);
            if (updateHash.containsKey(depComponent)) {
                    //Get version in updateHash. This will be the next active one
                String updateVer = (String)updateHash.get(depComponent);
                Log.update.debug(
                    Fmt.S("checkDependency: The version in updateHash for component %s"+
                          " is %s", depComponent,updateVer));
                if (!Util.compareVersionNumbers(updateVer,depVersion)) {
                    /*
                        well if the update goes thru then updateVer would be the version
                        of the active component. But the dependency is asking for another
                        version. this is an error
                    */
                    Log.update.debug(
                        Fmt.S("checkDependency: Component %s Dependent Version %s Update"+
                              " Version(Will be Active Next) %s",
                              depComponent,depVersion,updateVer));
                    Log.update.warning(6765,depComponent,depVersion,updateVer);
                    ++errorCount;

                }
                else {
                    Log.update.debug(
                        Fmt.S("checkDependency: Success Update: Component %s Dependent Version %s Update Version %s",
                              depComponent,depVersion,updateVer));
                }
                continue;
            }
                //Now load the BOM for the component
            String pathToBOMFile = Fmt.S(BOMFileLocation,depComponent);
            String activeVer = getActiveVersion(pathToBOMFile,depComponent,uninstallHash);
            if ((activeVer == null) || (activeVer.equals(Constants.NoActive))) {
                ++errorCount;
                Log.update.warning(6772,depComponent,"",pathToBOMFile);
                continue;
            }
            if (!Util.compareVersionNumbers(activeVer,depVersion)) {
                Log.update.debug(
                    Fmt.S("checkDependency: Component %s Dependent Version %s Active Version",
                          depComponent,depVersion,activeVer));
                Log.update.warning(6765,depComponent,depVersion,activeVer);
                ++errorCount;
            }
            else {
                Log.update.debug(
                    Fmt.S("checkDependency: Success Active: Component %s Dependent Version %s Active Version %s",
                          depComponent,depVersion,activeVer));
            }

        }
        if (errorCount!=0) {
            /**
                Could print a message saying you have this number of dependency errors
                All the warnings would have already been printed out
            */
            return false;
        }
        return true;
    }

    /**
        This method returns the active version of the component in BOM.
        If the current active version is being listed in the uninstallHash
        then this method finds the next version that will be active and will return that.
        In case of some error a null is returned.
        If there is no next active version then the string "noactive" is returned.
    */

    private  String getActiveVersion (String pathToBOMFile, String componentName,
                                      Map uninstallHash)
    {
        String activeVer = null;

        Element rootElement = Util.readXMLFile(pathToBOMFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.debug("Could no read BOM file %s.", pathToBOMFile);
            return null;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()==0)) {
            Log.update.debug("No %s tag present in the %s tag in the file %s",
                             Constants.ComponentTag,
                             Constants.BomTag,
                             pathToBOMFile);
            return null;
        }
            //Create a vector of all the version numbers
        List verNums = ListUtil.list();
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String status = component.getAttribute(Constants.StatusAttr);
            String version = component.getAttribute(Constants.VersionAttr);
            if (Constants.StatusAttrActive.equals(status)) {
                activeVer = version;
                verNums.add(version);
            }
            else if (!Constants.StatusAttrSuperceeded.equals(status)) {
                    //some other value
                Log.update.warning(6768,status,Constants.StatusAttr,
                                   Constants.ComponentTag,pathToBOMFile);
                return null;
            }
        }
        if (activeVer == null) {
            return Constants.NoActive;
        }
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String status = component.getAttribute(Constants.StatusAttr);
            String version = component.getAttribute(Constants.VersionAttr);
                //only add those versions numbers that are less than the current active one and the status is supeceeded
            if (Constants.StatusAttrSuperceeded.equals(status) &&
                (Util.compareVersionNumbersNoWildCards(version,activeVer)==1)) {
                 verNums.add(version);
            }
        }
        String uninstallVer = (String)uninstallHash.get(componentName);
        /*
            In case thee component needs to bee completely uninstalled we
            will be using the version as "*"
        */
        if (uninstallVer == null) {
            return activeVer;
        }
        if ("*".equals(uninstallVer)) {
            return Constants.NoActive;
        }
        else {
            /*
                lets see if the current active version is the one being uninstalled
                (it should be in theory) else you will be trying to uninstall something
                that does not exist
            */
            int result = Util.compareVersionNumbersNoWildCards(activeVer,uninstallVer);
            if (result == 3) {
                return null;
            }
            else if (result==0) {
                    //so now check whats the next version that will be active
                verNums.remove(activeVer);
                if (readComponentVersions) {
                    return (String)revertVersions.get(componentName);
                }
                else {
                        //in case we are using a <uninstall> tag to uninstall a component
                    return(determineNextActive(verNums));
                }
            }
            else {
                    //Like i said above this shouldn't happen in theory
                Log.update.warning(6771,uninstallVer,activeVer,componentName);
                return activeVer;
            }
        }
    }

    /**
        This method determines the newset version. If vector is empty it returns no Ac
    */
    private String determineNextActive (List v)
    {
        if (v.size()==0) {
                //this means there is no next active version. This is the last version
            return Constants.NoActive;
        }
        String nextActive = (String)v.remove(0);
        for (int i=0;i<v.size();++i) {
            String temp = (String)v.get(i);
            int result =
                Util.compareVersionNumbersNoWildCards(nextActive, temp);
            if (result == 1) {
                nextActive = temp;
            }
            if (result == 3) {
                return null;
            }
        }
        return nextActive;
    }
    /**
        Bulids a dependencyHash. This is a recursive method that goes
        recurses thru all the <dependency type="update"> tags.
        For <dependency type="component"> tags it just updates the hash.
        Eventually all  will be resolved to <dependency type="component">
        The keys in the hashtable will be of the form "name|version"
        One thing to note is there is no need to recurse thru the dependecy
        of an update that's being uninstalled. The list of updates being
        uninstalled is in uninstallUpdateHash

        Also when a update 1 depends on update2 then it means update 1 depends
        on
        <dependency> in update 1
        <component> in update 2 and
        <dependency> in update 2.
        But clearly it does not depend on <component> in update 1.
        Hence only if flag is true then the latter is processed.

    */
    private void buildDependencyHash (Element updateElement,
                                      Map dependencyHash, Map uninstallUpdateHash,boolean flag)
    {
        String updateName = updateElement.getAttribute(Constants.NameAttr);
        if (uninstallUpdateHash.containsKey(updateName)) {
                //you don't have to process the dependencies for an updates that's
                //being uninstalled
            Log.update.debug(
                Fmt.S("buildDependencyHash: %s is being uninstalled. Dependency will not"+
                      " be processed for it.", updateName));
            return;
        }

        if (flag) {
            NodeList componentList =
                Util.getNodeList(updateElement,Constants.ComponentTag);
            if (componentList != null ) {
                Log.update.debug("buildDependencyHash: Building <component> dependency for update %s.", updateName);
                for (int i=0;i<componentList.getLength();++i) {
                    Element compEl = (Element)componentList.item(i);
                    String compDepName =
                        compEl.getAttribute(Constants.NameAttr);
                    String compVersion =
                        compEl.getAttribute(Constants.VersionAttr);
                    Log.update.debug("buildDependencyHash : <component> Component %s Version %s",
                                     compDepName, compVersion);

                    dependencyHash.put(StringUtil.strcat(compDepName,"|",compVersion),
                                       compVersion);
                }
            }
        }

        NodeList dependencyList =
            Util.getNodeList(updateElement,Constants.DependencyTag);
        if (dependencyList != null ) {

            Log.update.debug("buildDependencyHash: Building <dependency> dependency for update %s.", updateName);
            for (int i=0;i<dependencyList.getLength();++i) {
                Element dependency = (Element)dependencyList.item(i);
                String dependencyName =
                    dependency.getAttribute(Constants.NameAttr);
                String type = dependency.getAttribute(Constants.TypeAttr);
                String version = dependency.getAttribute(Constants.VersionAttr);
                if (Constants.TypeAttrUpdate.equals(type)) {
                    Log.update.debug("buildDependencyHash: Update %s",
                                     dependencyName);
                    if (uninstallUpdateHash.containsKey(dependencyName)) {

                        Log.update.warning(6829,updateName,dependencyName,dependencyName);
                        ++dependencyErrorCount;
                        continue;
                    }
                        //read the UM file for the update given in the name attribute
                    String pathToUmFile =
                        Fmt.S(updateLocation,dependencyName,dependencyName);
                    Element rootElement = Util.readXMLFile(pathToUmFile,true);
                    if (rootElement == null) {
                            //Error could not read one of the UM files. Log and Continue
                        Log.update.debug(
                            Fmt.S("buildDependencyHash: Could not retrieve root element for document %s",
                                  pathToUmFile));
                        Log.update.warning(6766,pathToUmFile);
                        ++dependencyErrorCount;
                        continue;

                    }
                    Element ue = Util.getUpdateElementInUM(rootElement);
                    if (ue ==null) {
                            //Error could not read one of the UM files. Log and Continue
                        Log.update.debug(
                            Fmt.S("Element %s not defined in file %s",
                                  Constants.UpdateTag,
                                  pathToUmFile));
                        Log.update.warning(6758,pathToUmFile);
                        ++dependencyErrorCount;
                        continue;
                    }
                    buildDependencyHash(ue,dependencyHash,uninstallUpdateHash,true);
                }
                else if  (Constants.TypeAttrComponent.equals(type)) {
                    Log.update.debug("buildDependencyHash: <dependency> Component %s Version %s",
                                     dependencyName, version);

                    dependencyHash.put(StringUtil.strcat(dependencyName,"|",version),
                                       version);
                }
                else {
                        //wrong value for type. no need to stop. give warning and chug along
                    Log.update.warning(
                        6768,type,Constants.TypeAttr,Constants.UninstallTag,
                        dependencyName);
                    ++dependencyErrorCount;
                }
            }
        }
        return;
    }
    /**
        updates the uninstallHash with the component names and the corresponding
        version number of the components found in fileName(will be a UM file)
        Also updates the uninstallUpdateHash as follows
        This hash contains the uninstallUpdateName mapped to a ariba.tools.update.UpdateElement
        parh point tot the location of the instance being operated on.

    */
    private boolean updateUninstallHash (Map uninstallHash, String fileName,
                                         Map uninstallUpdateHash, String uninstallUpdateName,
                                         Map dependencyHash,
                                         Map updateHash,
                                         Map realUpdateHash,
                                         String currentUpdateName,
                                         List restoredComponents)
    {

        UpdateElement updateElement = null;


        Element rootElement = Util.readXMLFile(fileName,true);
        if (rootElement==null) {
            Log.update.warning(6758,fileName);
            return false;
        }
        Element update = Util.getUpdateElementInUM(rootElement);
        if (update == null) {
            Log.update.warning(6766,fileName);
            return false;
        }
        updateElement = new UpdateElement(
            update.getAttribute(Constants.NameAttr),
            update.getAttribute(Constants.VersionAttr),
            update.getAttribute(Constants.StatusAttr),
            update.getAttribute(Constants.TimeAttr),
            update.getAttribute(Constants.DescriptionAttr));

            //When uninstalling an update all other updates installed after this one must also be uninstalled.
            //Otherwise this cannot be uninstalled.
        List v = Util.getListOfUpdateAndBuildInfo(path);
            //get the Build number of the current build that's coming in.
        String currentBuildInfoCSV =
            StringUtil.strcat("tasks",Constants.fs,Constants.BuildInfo);
        int currentBuildNumber = getBuildNumber(currentBuildInfoCSV);
        if ((v==null) || (currentBuildNumber==0)) {
            Log.update.debug("Could not retrieve the build number");
            return false;
        }
        if (v.size()!=0) {
            UpdateAndBuildInfo u = (UpdateAndBuildInfo)ListUtil.lastElement(v);
            if (currentBuildNumber < u.getBuildNumber()) {
                Log.update.warning(6922,
                            currentUpdateName,u.getUpdateName());
                return false;
            }
        }


            //check if status of the update to be uninstalled on the customers instance
            //is "installed". if not log a message and then continue
        if (!Constants.UpdateStatusInstalled.equals(updateElement.getStatus())) {
            Log.update.warning(6794,updateElement.getUpdateName());
            return true;
        }

        /*
            This hash contains the list of components for the update being processed
            here. These components are going to be uninstalled and hence the variable
            name
        */
        Map updateElementUninstall = updateElement.getComponents();

        NodeList componentList = Util.getNodeList(update,Constants.ComponentTag);
        if (componentList!=null) {
                //there are some component tags. process them
            for (int i=0;i< componentList.getLength();++i) {
                Element component = (Element)componentList.item(i);
                String pathToBOM =
                    StringUtil.strcat(path,Fmt.S(Constants.BOMLocation,
                                                 component.getAttribute(Constants.NameAttr)));
                String compName = component.getAttribute(Constants.NameAttr);

                    //do not revert back to the previous version of a component if the component is not applicable to the OS
                if (isComponentNotValidForOS(compName)) {
                    continue;
                }

                String version = component.getAttribute(Constants.VersionAttr);
                ComponentElement cEl = getComponentInBOM(
                    pathToBOM,
                    compName,
                    version,
                    true);
                print(
                    Fmt.S("Component being uninstalled : %s %s in"+
                          " update %s.",
                          compName,version,update.getAttribute(Constants.NameAttr)));

                if (cEl == null ) {
                    Log.update.warning(6809,compName,version,pathToBOM);
                    return false;
                }
                    //cannot uninstall a component which is not present
                    //cannot uninstall a component which is not active
                if (!cEl.getStatus().equals(Constants.StatusAttrActive)) {
                    Log.update.info(6792,
                                    cEl.getComponentName(),
                                    cEl.getVersion(),
                                    pathToBOM);
                }
                else {
                    uninstallHash.put(compName,version);
                    updateElementUninstall.put(compName,cEl);
                }
            }
        }

        uninstallUpdateHash.put(uninstallUpdateName,updateElement);

        /*
            Now go thru the list of <uninstall> tags in the update that's
            being uninstalled. The components/updates in the <uninstall>
            tag would have been uninstalled when the update was originally
            installed. So now those components/updates need to be restored.

            Treat this as another component that needs to be installed.
            But you will need to maintain a vector of updates that
            are being restored so that at the end their status can be changed
            to installed.
        */


        NodeList uninstallList = Util.getNodeList(update,Constants.UninstallTag);
        if (uninstallList!=null) {
                //there are some uninstall tags. process them
            for (int i=0;i< uninstallList.getLength();++i) {
                Element uninstall = (Element)uninstallList.item(i);
                String type = uninstall.getAttribute(Constants.TypeAttr);
                String uninstallName =
                    uninstall.getAttribute(Constants.NameAttr);
                String version = uninstall.getAttribute(Constants.VersionAttr);
                if (Constants.TypeAttrComponent.equals(type)) {
                    if (updateHash.containsKey(uninstallName)) {

                        Log.update.warning(6861,uninstallName,
                                           version,updateHash.get(uninstallName));
                        return false;
                    }
                        //no need to install a component that a uninstalled previously if this component is not applicable to the OS.
                    if (isComponentNotValidForOS(uninstallName)) {
                        continue;
                    }

                    if ("*".equals(version)) {
                            //get version which was last uninstalled.
                            //get this by lookig at the timestamp of the component in the bom

                        String pToB =
                            StringUtil.strcat(path,
                                              Fmt.S(Constants.BOMLocation,uninstallName));
                        String lastActiveVersion =
                            getLastActive(pToB,uninstallName);
                        if (lastActiveVersion==null) {
                            Log.update.warning(6918,pToB);
                            return false;
                        }
                        Log.update.debug("Component %s was uninstalled with version *." +
                                         "Will be restored with %s", uninstallName, lastActiveVersion);

                        uninstall.setAttribute(Constants.VersionAttr,lastActiveVersion);
                    }
                    if (!updateInstallHashes(uninstall,dependencyHash,
                                             updateHash,realUpdateHash,restoredComponents,
                                             currentUpdateName)) {

                        Log.update.debug("updateUninstallHash: Could not intialize"+
                                         "component %s %s that needed to be restored",
                                               uninstallName, version);
                        return false;
                    }
                }
                else if (Constants.TypeAttrUpdate.equals(type)) {
                    /*
                        Backup the manifest, load the components.
                        XXX: this is not done for the time being because
                        if installation of an unistalled update fails then there
                        is no roll back. ie. no recursive roll back.
                    */
                    /*
                        String backupLocation = StringUtil.strcat(
                        Fmt.S(Constants.UpdateBackUp,path,currentUpdateName),
                        uninstallName,".xml");
                        File bacupLocationFile = new File(backupLocation);

                        String manifestName = Fmt.S(updateLocation,
                        uninstallName,uninstallName);
                        Log.update.debug(
                        Fmt.S("updateUninstallHash: Backing up the manifest file for update %s",
                        uninstallName));
                        if (!IOUtil.copyFile(new File(manifestName), bacupLocationFile)) {
                        Log.update.info(6806,manifestName,backupLocation);
                        return false;
                        }

                            //call method recursively
                        if (!updateUninstallHash(uninstallHash,manifestName,
                        uninstallUpdateHash,uninstallName,dependencyHash,
                        updateHash,realUpdateHash,currentUpdateName,
                        restoredComponents)) {
                            //was not able to process the <uninstall type="update"> element
                        Log.update.debug(
                        Fmt.S("updateUninstallHash: Could  not process the manifest file %s for the update %s",
                        manifestName,uninstallName));
                        Log.update.warning(6758,manifestName);
                        return false;
                        }
                    */
                    Log.update.debug("Will not recursively install the uninstalled"+
                                     "uupdate %s.",uninstallName);
                }
                else {
                    Log.update.warning(6768,type,Constants.TypeAttr,Constants.UninstallTag,
                                       uninstallName+".xml");
                }
            }
        }

        return true;

    }

    /**
        Returns the version number of the component whose status was last set to false.
        Returns null if there is a version number witht status=active
    */
    private String getLastActive (String bomFile, String compName)
    {
        Element rootElement = Util.readXMLFile(bomFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.warning(6766,bomFile);
            return null;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()==0)) {
            Log.update.warning(6767,Constants.ComponentTag,Constants.BomTag,
                               bomFile);
            return null;
        }

        long recent = 0;
        String version = null;
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String time = component.getAttribute(Constants.TimeAttr);
            if (Constants.StatusAttrActive.equals(
                    component.getAttribute(Constants.StatusAttr))) {

                Log.update.debug("No version in file %s should be active", bomFile);
                return null;
            }
            long temp = convertToLong(time);
            if (temp==-1) {
                return null;
            }
            if (temp > recent) {
                recent = temp;
                version = component.getAttribute(Constants.VersionAttr);
            }
        }
        return version;
    }

    /**
        This method version of the component whose status is "status".
        Returns null if there are two versions with status equal to "status"
    */

    private  String getVersionWithStatus (String pathToBOMFile, String componentName,
                                          String status)
    {

        String ver = null;

        Element rootElement = Util.readXMLFile(pathToBOMFile,false);
        if (rootElement == null) {
                //could not load BOM file
            Log.update.warning(6766,pathToBOMFile);
            return null;
        }

        NodeList nl = Util.getNodeList(rootElement, Constants.ComponentTag);
        if ((nl==null) || (nl.getLength()==0)) {
            Log.update.warning(6767,Constants.ComponentTag,Constants.BomTag,
                               pathToBOMFile);
            return null;
        }
            //Create a vector of all the version numbers
        int count = 0;
        for (int i=0;i<nl.getLength();++i) {
            Element component = (Element)nl.item(i);
            String compStatus = component.getAttribute(Constants.StatusAttr);
            if (status.equals(compStatus)) {
                ver = component.getAttribute(Constants.VersionAttr);
                ++count;
            }

        }
        if (count!=1) {
            return null;
        }
        return ver;
    }



    private boolean updateInstallHashes (Element component, Map dependencyHash,
                                         Map updateHash, Map realUpdateHash,List restoredComponents,
                                         String currentUpdateName)
    {
        String activeVersion = null;

        /*
            Note : This assumes you cant ship two different versions of the same component
            in the same update. In case you do this then the one read last will take precedence.
        */
        String componentName = component.getAttribute(Constants.NameAttr);
        String version = component.getAttribute(Constants.VersionAttr);
        String compInBackup =
            StringUtil.strcat(path,Fmt.S(Constants.BOMLocation,componentName));
        if ("*".equals(version)) {
                //Don't resore components uninstalled wih versrion="*"
                //This means that you this was completely uninstalled the previous time.
                //Hence the version unistalleed will have a status = deleted. get this version
            /*
                version = getVersionWithStatus(compInBackup,
                componentName,
                Constants.StatusAttrDeleted);
                if (version == null) {
                Log.update.debug("Could not get version number for component %s"+
                "in file %s with status %s",componentName,compInBackup,
                Constants.StatusAttrDeleted);
                return false;
                }
            */
            Log.update.debug("Component %s was uninstalled with version *."+
                             "Will not be restored.", componentName);
            return true;
        }
        print(
            Fmt.S("Component %s %s will be installed with this update",
                  componentName,
                  version));

        ComponentElement tempCompElement = getComponentInBOM(
            compInBackup,
            componentName,
            version,false);
        if ((tempCompElement==null) ||
            (Constants.CompNotFnd.equals(tempCompElement.getStatus()))) {
            Log.update.warning(6809,componentName,version,compInBackup);
            return false;
        }
        List v = tempCompElement.getDependencies();
        /*
            This is the list of dependencies for this component in the BOM.
            There is no version number so we will use "*" ie.
            any version is fine as long as it will be active"
        */
        for (int j=0;j<v.size();++j) {
            String compName = (String)v.get(j);
            String key = StringUtil.strcat(compName,"|","*");
            dependencyHash.put(key,"*");
        }
        /*
            Now initialize tempCompElement.nextActive(). This actually
            will be the current active version of the component in the
            instance if any. The BOM for this HAS to exist in the instance since
            it had previously been uninstalled (so status will be superceded)
        */

        String pathToBOMFileIninstance = StringUtil.strcat(path,
                                                           Fmt.S(Constants.BOMLocation,componentName));

        activeVersion = getActiveVersion(pathToBOMFileIninstance,
                                         componentName,
                                         MapUtil.map());
        if (activeVersion == null) {
            Log.update.debug(
                Fmt.S("processUpdate: Error in getting the active component in %s",
                      pathToBOMFileIninstance));
            return false;
        }
        if (activeVersion.equals(Constants.NoActive)) {
            Log.update.debug(
                Fmt.S("processUpdate: No version of %s is active",
                      componentName));
            tempCompElement.setCICStatus(Constants.NoActive);
                //implies an entry needs to be put back intto product.csv
            if (!backupProductCSV(currentUpdateName)) {
                return false;
            }
            restoredComponents.add(componentName);
        }
        else {
                //Give a warning saying what the current active version is and mention
                //that it will be overwritten by the version that is being restored.
            Log.update.info(6860,componentName,activeVersion,version);
            ComponentElement ce = getComponentInBOM(pathToBOMFileIninstance,
                                                    componentName,activeVersion,false);
            if (ce==null) {
                Log.update.warning(6809,componentName,activeVersion,
                                   pathToBOMFileIninstance);
                return false;
            }
            tempCompElement.setCICStatus(ce.getVersion());
            tempCompElement.setNextActive(ce);
        }
        updateHash.put(componentName,version);
        realUpdateHash.put(componentName,tempCompElement);

        return true;
    }

    /**
        Utility method to print out key=value in a hash. Each print is preceeded
        by text

        Not used in production code. Used for debugging while development.
    */
    private void printHash (Map hash, String text)
    {
        Iterator e = hash.keySet().iterator();
        while (e.hasNext()) {
            String key = (String)e.next();
            String version = (String)hash.get(key);
            console(text +" : " + key +"="+version);
        }
    }

    /*
        Not used in production code. Used for debugging while development.
    */
    private void print (String msg)
    {
        report(msg);
        Log.update.info(6841,msg);
    }

    /**
        Prints a message to the console
    */
    private void console (String msg)
    {
        if (!silentMode) {
            System.out.println(msg);
        }
    }
    /**
        Converts a byte array to hex string
    */
    private String toHex (byte[] b)
    {
        if (b == null) {
            return null;
        }
        FastStringBuffer sb = new FastStringBuffer();

        for (int i = 0; i < b.length; i++) {
            int j = ((int) b[i]) & 0xFF;

            char first = Constants.HexChars.charAt(j / 16);
            char second = Constants.HexChars.charAt(j % 16);

            sb.append(first);
            sb.append(second);
        }

        return sb.toString();
    }

    /**
        This method strips ^M character from a file and saves them into a new
        file. This method was taken from
            //ariba/release/7.0/current/bin/install/Installshield-Unix/java/AribaRemoveCtrlMAction.java
    */
    private void inputStreamToOutputStreamRemovingByte (
        InputStream  in,
        OutputStream out,
        byte[]       buffer,
        byte         b)
      throws IOException
    {
        while (true) {
            int n = in.read(buffer);
            if (n == -1) {
                break;
            }
            int left  = 0;
            int right = left;
            for (; right < n ; right++) {
                if (buffer[right] == b) {
                    out.write(buffer, left, right - left);
                    left = right + 1;
                }
            }
            out.write(buffer, left, right - left);
        }
        out.flush();
    }

    /**
        This method adds ^M character to a file and saves it into a new
        file. This method was taken from
            //ariba/release/7.0/current/bin/install/Installshield-Unix/java/AribaRemoveCtrlMAction.java
    */
    private void inputStreamToOutputStreamAddingByte (
        InputStream  in,
        OutputStream out,
        byte[]       buffer,
        byte         b)
      throws IOException
    {
        while (true) {
            int n = in.read(buffer);
            if (n == -1) {
                break;
            }
            int left  = 0;
            int right = left;
            for (; right < n ; right++) {
                if (buffer[right] == (byte)'\n') {
                    out.write(buffer, left, right - left);
                    out.write(b);
                    left = right;
                }
            }
            out.write(buffer, left, right - left);
        }
        out.flush();
    }

    /**
     * Checks if a component needs to be updated/installed on a OS
     * @param compName Name of the component
     * @return true if component is not valid for OS
     */
    private boolean isComponentNotValidForOS (String compName)
    {
         //Check if this components needs to be installed or not.
        String osInExclude = (String)excludeBOM.get(compName);
        if ((osInExclude != null) &&
            (!osName.equals(osInExclude))) {
                //implies entry for this componet is not there
            Log.update.debug(
                    Fmt.S("Component %s will not be updated as it is" +
                              "not needed on the OS : %s.",compName,osName));
             return true;
        }
        return false;
    }

    /**
     * This method returns the build number in a BuildInfo.csv file.
     * Returns 0 if it cant find one
     * @param fileName The BuildInfo.csv location
     * @return
     */
    private int getBuildNumber (String fileName)
    {
        List buildInfoVec=null;
        try {
            buildInfoVec = CSVReader.readAllLines(new File(fileName),
                        SystemUtil.getFileEncoding());
        }
        catch (IOException ioe) {
            Log.update.debug(
                Fmt.S("getListOfUpdateAndBuildInfo: Error while reading file %s : %s",
                    fileName, ioe.getMessage()));
            return 0;
        }
        int buildNumber=0;
        for (int j=0;j<buildInfoVec.size();++j) {
            List line = (List)buildInfoVec.get(j);
            if ((line.size()==0) || (line.size() !=2)) {
                    //ignore empty lines and lines with != 2 values
                continue;
            }
            String temp = (String)ListUtil.firstElement(line);
            try {
                if (Constants.BuildNumber.equals(temp)) {
                    buildNumber = IntegerFormatter.parseInt((String)line.get(1));
                }
            }
            catch (ParseException nfe) {
                Log.update.debug(
                    Fmt.S("Exception while reading the file %s : %s",
                        fileName, nfe.getMessage()));
                return 0;
            }
        }
        return buildNumber;
    }

    /**
        Shortens filepaths by making use of Windows virtual drives.
        The following paths are affected: BaseImage, ComponentInDepot
        and product root.
    */
    private void setVirtualDrives()
    {
        String d = "IJKLMNOPQRSTUVWXYZ";
        drive[0] = (new File(Constants.CoreDepotRoot)).getAbsolutePath();
        drive[1] = (new File(Constants.DepotRoot)).getAbsolutePath();
        drive[2] = (new File(path)).getAbsolutePath();

        if (!Util.execute(Constants.SubstCmd)) {
                // Failed to invoke subst, no reason to continue.
                // No paths will be changed.
            return;
        }

        int j = 0;
        for (int i = 0; i < d.length(); i++) {
            String vd = d.charAt(i) + ":";
            String cmd =
                StringUtil.strcat(Constants.SubstCmd, vd, " ", drive[j]);
            if (Util.execute(cmd)) {
                Log.update.info(6930, vd, drive[j]);
                drive[j] = vd;
                if (++j == drive.length) {
                    break;
                }
            }
        }

        if (j > 0) {
            StringBuffer sb = new StringBuffer(ComponentInDepotDir);
            sb.replace(0, Constants.CoreDepotRoot.length()-1, drive[0]);
            ComponentInDepotDir = sb.toString();
        }
        if (j > 1) {
            StringBuffer sb = new StringBuffer(BaseImageDir);
            sb.replace(0, Constants.DepotRoot.length()-1, drive[1]);
            BaseImageDir = sb.toString();
        }
        if (j == drive.length) {
            StringBuffer sb = new StringBuffer(path);
            sb.replace(0, path.length(), drive[2]);
            path = sb.toString();
        }
    }

    /**
        Executes any clean up tasks before returning to TaskHarness.
    */
    private void finalizeUDT()
    {
        if (ServerUtil.isWin32 &&
            (CommonKeys.analysis.equalsIgnoreCase(product) ||
            CommonKeys.acm.equalsIgnoreCase(product))) {
            unsetVirtualDrives();
        }
    }

    private void unsetVirtualDrives()
    {
        for (int i = 0; i < drive.length; i++) {
            String cmd =
                StringUtil.strcat(Constants.SubstCmd, drive[i], " /d");
            if (Util.execute(cmd)) {
                Log.update.info(6931, drive[i]);
            }
        }
    }
}
