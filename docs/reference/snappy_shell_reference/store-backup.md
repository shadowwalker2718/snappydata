# backup
Creates a backup of operational disk stores for all members running in the distributed system. Each member with persistent data creates a backup of its own configuration and disk stores.

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_06BC55B8DB414173BBD71BEFB9F8F1BD"><p !!!
!!!Note:
	 SnappyData does not support backing up disk stores on systems with live transactions, or when concurrent DML statements are being executed. See <a href="../../disk_storage/backup_restore_disk_store.html#backup_restore_disk_store" class="xref" title="When you invoke the snappy-shell backup command, SnappyData backs up disk stores for all members that are running in the distributed system at that time. Each member with persistent data creates a backup of its own configuration and disk stores.">Backing Up and Restoring Disk Stores</a>.</p>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_B06049F2187548D2A567EB7C2AF6F1A6" class="xref">Syntax</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_C75C621FB18D435D94E93BD865BA35E0" class="xref">Description</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_E151532922C349FA99C2120880E82D1F" class="xref">Prerequisites and Best Practices</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_FEB691B4C9664C31A980B5AB1C1045F3" class="xref">Specifying the Backup Directory</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_19EAC375FDBE43FB922EA3E99F41B07E" class="xref">Example</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_F65D456EEE55433E9C6F6EC9B4057734" class="xref">Output Messages from snappy-shell backup</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_70860E525F5C4F5D995551846E007AC8" class="xref">Backup Directory Structure and Its Contents</a>
-   <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_050663B03C0A4C42B07B4C5F69EAC95D" class="xref">Restoring an Online Backup</a>

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_B06049F2187548D2A567EB7C2AF6F1A6"></a>

#Syntax

Use the mcast-port and -mcast-address, or the -locators options, on the command line to connect to the SnappyData cluster.

``` pre
snappy-shell backup [-baseline=<baseline directory>] <target directory> [-J-D<vmprop>=<prop-value>]
 [-mcast-port=<port>] [-mcast-address=<address>]
 [-locators=<addresses>] [-bind-address=<address>] [-<prop-name>=<prop-value>]*
```

Alternatively, you can specify these and other distributed system properties in a <span class="ph filepath">gemfirexd.properties</span> file that is available in the directory where you run the `snappy-shell` command.

The table describes options for snappy-shell backup.

|Option|Description|
|-|-|
|-baseline|The directory that contains a baseline backup used for comparison during an incremental backup. The baseline directory corresponds to the date when the original backup command was performed, rather than the backup location you specified (for example, a valid baseline directory might resemble <span class="ph filepath">/export/fileServerDirectory/gemfireXDBackupLocation/2012-10-01-12-30</span>).</br>An incremental backup operation backs up any data that is not already present in the specified <code class="ph codeph">-baseline</code> directory. If the member cannot find previously backed up data or if the previously backed up data is corrupt, then command performs a full backup on that member. (The command also performs a full backup if you omit the <code class="ph codeph">-baseline</code> option.|
|&lt;target-directory&gt;|The directory in which SnappyData stores the backup content. See <a href="store-backup.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_FEB691B4C9664C31A980B5AB1C1045F3" class="xref">Specifying the Backup Directory</a>.|
|-mcast-port|Multicast port used to communicate with other members of the distributed system. If zero, multicast is not used for member discovery (specify `-locators` instead). </br>Valid values are in the range 0–65535, with a default value of 10334.|
|-mcast-address|Multicast address used to discover other members of the distributed system. This value is used only if the `-locators` option is not specified.</br>The default multicast address is 239.192.81.1.|
|-locators|List of locators used to discover members of the distributed system. Supply all locators as comma-separated host:port values.|
|-bind-address|The address to which this peer binds for receiving peer-to-peer messages. By default SnappyData uses the hostname, or localhost if the hostname points to a local loopback address.|
|-prop-name|Any other SnappyData distributed system property.|

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_C75C621FB18D435D94E93BD865BA35E0"></a>

#Description

An online backup saves the following:

-   For each member with persistent data, the backup includes disk store files for all stores containing persistent table data.
-   Configuration files from the member startup.
-   gemfirexd.properties, with the properties the member was started with.
-   A restore script, written for the member's operating system, that copies the files back to their original locations. For example, in Windows, the file is restore.bat and in Linux, it is restore.sh.

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_E151532922C349FA99C2120880E82D1F"></a>

#Prerequisites and Best Practices

-   Run this command during a period of low activity in your system. The backup does not block system activities, but it uses file system resources on all hosts in your distributed system and can affect performance.
-   Do not try to create backup files from a running system using file copy commands. You will get incomplete and unusable copies.
-   Make sure the target backup directory directory exists and has the proper permissions for your members to write to it and create subdirectories.
-   You might want to compact your disk store before running the backup. See the <a href="store-compact-all-disk-stores.html#reference_13F8B5AFCD9049E380715D2EF0E33BDC" class="xref noPageCitation" title="Perform online compaction of SnappyData disk stores.">compact-all-disk-stores</a> command.
-   Make sure that those SnappyData members that host persistent data are running in the distributed system. Offline members cannot back up their disk stores. (A complete backup can still be performed if all table data is available in the running members.)

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_FEB691B4C9664C31A980B5AB1C1045F3"></a>

#Specifying the Backup Directory

The directory you specify for backup can be used multiple times. Each backup first creates a top level directory for the backup, under the directory you specify, identified to the minute. You can use one of two formats:

-   Use a single physical location, such as a network file server. (For example, /export/fileServerDirectory/snappyStoreBackupLocation).
-   Use a directory that is local to all host machines in the system. (For example, ./snappyStoreBackupLocation).

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_19EAC375FDBE43FB922EA3E99F41B07E"></a>

#Example

Using a backup directory that is local to all host machines in the system:

``` pre
snappy-shell backup  ./snappyStoreBackupLocation
  -locators=warsaw.pivotal.com[26340]
```

See also <a href="../../disk_storage/backup_restore_disk_store.html#backup_restore_disk_store" class="xref" title="When you invoke the snappy-shell backup command, SnappyData backs up disk stores for all members that are running in the distributed system at that time. Each member with persistent data creates a backup of its own configuration and disk stores.">Backing Up and Restoring Disk Stores</a>.

To perform an incremental backup at a later time:

``` pre
snappy-shell backup -baseline=./snappyStoreBackupLocation/2012-10-01-12-30 ./snappyStoreBackupLocation 
  -locators=warsaw.pivotal.com[26340] 
```

<p class="note"><strong>Note:</strong> SnappyData does not support taking incremental backups on systems with live transactions, or when concurrent DML statements are being executed.</p>

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_F65D456EEE55433E9C6F6EC9B4057734"></a>

#Output Messages from snappy-shell backup

When you run `snappy-shell backup`, it reports on the outcome of the operation.

If any members were offline when you run `snappy-shell backup`, you get this message:

``` pre
The backup may be incomplete. The following disk
stores are not online:  DiskStore at hostc.pivotal.com
/home/dsmith/dir3
```

A complete backup can still be performed if all table data is available in the running members.

The tool reports on the success of the operation. If the operation is successful, you see a message like this:

``` pre
Connecting to distributed system: locators=warsaw.pivotal.com26340
The following disk stores were backed up:
DiskStore at hosta.pivotal.com /home/dsmith/dir1
DiskStore at hostb.pivotal.com /home/dsmith/dir2
Backup successful.
```

If the operation does not succeed at backing up all known members, you see a message like this:

``` pre
Connecting to distributed system: locators=warsaw.pivotal.com26357
The following disk stores were backed up:
DiskStore at hosta.pivotal.com /home/dsmith/dir1
DiskStore at hostb.pivotal.com /home/dsmith/dir2
The backup may be incomplete. The following disk stores are not online:
DiskStore at hostc.pivotal.com /home/dsmith/dir3
```

A member that fails to complete its backup is noted in this ending status message and leaves the file INCOMPLETE\_BACKUP in its highest level backup directory.

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_70860E525F5C4F5D995551846E007AC8"></a>

#Backup Directory Structure and Its Contents

Below is the structure of files and directories backed up in a distributed system:

``` pre
 2011-05-02-18-10 /:
pc15_8933_v10_10761_54522
2011-05-02-18-10/pc15_8933_v10_10761_54522:
config diskstores README.txt restore.sh
2011-05-02-18-10/pc15_8933_v10_10761_54522/config:
gemfirexd.properties
2011-05-02-18-10/pc15_8933_v10_10761_54522/diskstores:
GFXD_DD_DISKSTORE
2011-05-02-18-10/pc15_8933_v10_10761_54522/diskstores/GFXD_DD_DISKSTORE:
dir0
2011-05-02-18-10/pc15_8933_v10_10761_54522/diskstores/GFXD_DD_DISKSTORE/dir0:
BACKUPGFXD-DD-DISKSTORE_1.crf
BACKUPGFXD-DD-DISKSTORE_1.drf BACKUPGFXD-DD-DISKSTORE_2.crf
BACKUPGFXD-DD-DISKSTORE_2.drf BACKUPGFXD-DD-DISKSTORE.if
```

<a id="reference_13F8B5AFCD9049E380715D2EF0E33BDC__section_050663B03C0A4C42B07B4C5F69EAC95D"></a>

#Restoring an Online Backup

The restore script (<span class="ph filepath">restore.sh</span> or <span class="ph filepath">restore.bat</span>) copies files back to their original locations. You can do this manually if you wish:

1.  Restore your disk stores when your members are offline and the system is down.
2.  Read the restore scripts to see where they will place the files and make sure the destination locations are ready. The restore scripts refuse to copy over files with the same names.
3.  Run the restore scripts. Run each script on the host where the backup originated.

The restore copies these back to their original location.