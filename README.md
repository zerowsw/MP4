# Sava - a distributed graph processing system
We build a distributed grpah processing system named Save to process large
graphs, for arbitray functions. And we store the input graph and output results 
in a simple distributed file system developed by ourselves. <br>
Then, based on save, we developed two applications - PageRank and Single Source 
Shortest Path(SSSP). The two applications are modulars and developed through API
of Sava.<br>
To Run the code, first, you should choose one VM as a master, one VM as a stand 
by master, one Vm as a client which is responsible for getting your command and 
transporting it to the master. The rest VMs are workers.<br>
Since our project is built based on maven, thus, you can use commnd mvn assembly
: assembly to package our project into a executable jar file - mp4.jar.<br>
Then, upload the jar file to all the VMs. <br>
cd into the file directory of jar file. <br>
Use java -jar xxx.jar master to start a master<br>
Use java -jar xxx.jar standby to start a standby master<br>
use java -jar xxx.jar client to start a client and recieve you command<br>
use java -jar xxx.jar worker to start a worker which is the main calcualtion 
unit. <br>
To start an application, you should input the class name of vertex of the 
application and the file address of the large graph.<br>
Then, after calculation, it should output the top 25 vertices and their values.<br>
