package client;

import client.cmt11x.DataPackRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.LinkedTreeMap;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;

/**
 * Created by Derek on 16/06/2016.
 */
public abstract class VlocityPackage {

    protected VlocityClient Client;

    public VlocityPackage(VlocityClient client) {
        this.Client = client;
    }

    public abstract String getPackageName();
    public abstract String getPackageVersion();
    public abstract VlocityArtifact InitialiseArtifact(ArtifactTypesEnum artifactType) throws ArtifactNotSupportedException;
    public abstract Class GetArtifactClass(ArtifactTypesEnum artifactTypeName) throws ArtifactNotSupportedException;

    public SoqlQueryStringBuilder getSoqlQueryStringBuilder(ArtifactTypesEnum artifactType) throws ArtifactNotSupportedException, PackageNotSupportedException, VersionNotSupportedException {
        VlocityArtifact artifact = InitialiseArtifact(artifactType);

        return artifact.getQueryStringBuilder();

    }

    public ArrayList<VlocityArtifact> GetArtifacts(ArtifactTypesEnum artifactType) throws Exception {
        SoqlQueryStringBuilder builder = getSoqlQueryStringBuilder(artifactType);

        return GetArtifacts(artifactType, builder);

    }

    public ArrayList<VlocityArtifact> GetArtifacts(ArtifactTypesEnum artifactType, ArrayList<String> names) throws Exception {
        SoqlQueryStringBuilder builder = getSoqlQueryStringBuilder(artifactType);

        if (names != null && names.size() > 0) {
            builder.AddCondition("Name", SoqlQueryStringBuilder.ComparisonOperatorEnum.In, names);
        }

        return GetArtifacts(artifactType, builder);
    }

    public ArrayList<VlocityArtifact> GetArtifacts(ArtifactTypesEnum artifactType, String lastModifiedByUserName) throws Exception {
        SoqlQueryStringBuilder builder = getSoqlQueryStringBuilder(artifactType);

        if (lastModifiedByUserName != null && !lastModifiedByUserName.isEmpty()) {
            builder.AddCondition("LastModifiedBy.Username", SoqlQueryStringBuilder.ComparisonOperatorEnum.Equals, lastModifiedByUserName);
        }

        return GetArtifacts(artifactType, builder);
    }

    public ArrayList<VlocityArtifact> GetArtifacts(ArtifactTypesEnum artifactType, SoqlQueryStringBuilder queryBuilder) throws Exception {
        ArrayList<VlocityArtifact> artifacts = new ArrayList<>();

        if (this.Client.getPartnerApiConnection() == null) {
            throw new NotLoggedInException();
        }

        QueryResult queryResults = this.Client.getPartnerApiConnection().query(queryBuilder.toString());

        do {

            if ((queryResults != null) && (queryResults.getSize() > 0)) {
                for (SObject record : queryResults.getRecords()) {
                    VlocityArtifact artifact = InitialiseArtifact(artifactType);
                    artifact.setProperties(record);

                    if (artifact.hasDataPack()) {
                        artifact.Datapack = getDatapack(artifact.getDataPackType(), record.getId());
                    }

                    artifact.onAfterRetrieve();
                    artifacts.add(artifact);
                }
            }

            if (queryResults.isDone()) {
                queryResults = null;
            }
            else {
                queryResults = this.Client.getPartnerApiConnection().queryMore(queryResults.getQueryLocator());
            }

        }
        while (queryResults != null);

        return artifacts;
    }

    protected String getDatapack(String artifactType, String artifactId) throws IOException, UnexpectedResponseException, UnexpectedDataPackException {

        URL partnerUrl = new URL(this.Client.getServerUrl());

        URL dataPackUri = new URL(partnerUrl.getProtocol(), partnerUrl.getHost(), "/services/apexrest/"+ this.getPackageName() + "/v1/VlocityDataPacks/");

        DataPackRequest requestData = new  DataPackRequest("Export", artifactType, artifactId);

        HttpClient httpClient = HttpClientBuilder.create().build();

        HttpPost dpCreateMethod = new HttpPost(dataPackUri.toExternalForm());
        dpCreateMethod.setHeader("Authorization", "Bearer " + Client.getSessionId());
        dpCreateMethod.setHeader("Content-Type", "application/json; charset=UTF-8");
        dpCreateMethod.setHeader("Accept", "application/json");
        dpCreateMethod.setHeader("Accept-Charset", "UTF-8");

        String status;

        Gson gson = new GsonBuilder().create();

        do {

            dpCreateMethod.setEntity(serialiseRequest(requestData));

            HttpResponse response = httpClient.execute(dpCreateMethod);

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new UnexpectedResponseException(generateRequestResponseString(dpCreateMethod, response));
            }

            String resultText = EntityUtils.toString(response.getEntity());

            LinkedTreeMap result;

            try {
                result = gson.fromJson(resultText, LinkedTreeMap.class);
            }
            catch (com.google.gson.JsonSyntaxException ex) {
                throw new UnexpectedResponseException(generateRequestResponseString(dpCreateMethod, response));
            }

            requestData.processData.VlocityDataPackId = (String)result.get("VlocityDataPackId");
            status = (String)result.get("Status");

            if ("Error".equals(status)) {
                String message = (String)result.get("Message");
                throw new UnexpectedDataPackException("Unable to extract datapack " + requestData.processData.VlocityDataPackId + " for " + artifactType + " " + artifactId + ". Error message is \n" + message);
            }
        }
        while ("Ready".equals(status) || "InProgress".equals(status));


        dataPackUri = new URL(dataPackUri, requestData.processData.VlocityDataPackId);

        HttpGet getDpMethod = new HttpGet(dataPackUri.toExternalForm());
        getDpMethod.setHeader("Authorization", "Bearer " + Client.getSessionId());
        getDpMethod.setHeader("Accept", "application/json");
        getDpMethod.setHeader("Accept-Charset", "UTF-8");

        HttpResponse response = httpClient.execute(getDpMethod);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new UnexpectedResponseException(generateRequestResponseString(getDpMethod, response));
        }

        String resultText = EntityUtils.toString(response.getEntity());

        return resultText;

    }

    protected HttpEntity serialiseRequest(DataPackRequest requestData) throws UnsupportedEncodingException {
        Gson gson = new GsonBuilder().create();
        String bodyText = gson.toJson(requestData);
        HttpEntity entity = new ByteArrayEntity(bodyText.getBytes("UTF-8"));

        return entity;
    }

    protected String generateRequestString(HttpRequestBase method) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(method.getMethod() + " " + method.getURI().toURL().toExternalForm());

        for (Header header : method.getAllHeaders()) {
            lines.add(header.getName() + ": " + header.getValue());
        }

        if (method.getClass() == HttpPost.class) {
            lines.add(EntityUtils.toString(((HttpPost)method).getEntity()));
        }

        return String.join("\n", lines) + "\n";
    }

    protected String generateRequestResponseString(HttpRequestBase method, HttpResponse response) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(generateRequestString(method));

        lines.add(String.valueOf(response.getStatusLine().getProtocolVersion().toString() + " " + response.getStatusLine().getStatusCode() + "/" + response.getStatusLine().getReasonPhrase()));

        for (Header header : response.getAllHeaders()) {
            lines.add(header.getName() + ": " + header.getValue());
        }

        lines.add(EntityUtils.toString(response.getEntity()));

        return String.join("\n", lines) + "\n";
    }

    public void Deploy(ArrayList<VlocityArtifact> artifacts) throws UnexpectedResponseException, UnexpectedDataPackException, IOException {
        /*if (artifacts == null || artifacts.size() == 0) return;

        if (artifacts.get(0).hasDataPack()) {
            for (VlocityArtifact artifact : artifacts) {
                artifact.onBeforeDeploy();
                DeployDataPack(artifact);
            }
        }
        else {
            for (VlocityArtifact artifact : artifacts) {
                artifact.onBeforeDeploy();
                DeployDataPack(artifact);
            }
        }*/
    }

    private void DeploySObject(ArrayList<VlocityArtifact> artifacts) {
        ArrayList<SObject> sObjects = new ArrayList<>();

        artifacts.forEach(a -> {
            a.onBeforeDeploy();
            sObjects.add(a.ToSObject());
        });

        //Client.getPartnerApiConnection().update(artifacts);
    }

    private void DeployDataPack(VlocityArtifact artifact)  throws IOException, UnexpectedResponseException, UnexpectedDataPackException {
        /*URL partnerUrl = new URL(this.Client.getServerUrl());

        URL dataPackUri = new URL(partnerUrl.getProtocol(), partnerUrl.getHost(), "/services/apexrest/"+ this.getPackageName() + "/v1/VlocityDataPacks/");

        DataPackRequest requestData = new  DataPackRequest("Import", artifact.getDataPackType());
        requestData.setDataPackContent(artifact.Datapack);

        HttpClient httpClient = HttpClientBuilder.create().build();

        HttpPost dpCreateMethod = new HttpPost(dataPackUri.toExternalForm());
        dpCreateMethod.setHeader("Authorization", "Bearer " + Client.getSessionId());
        dpCreateMethod.setHeader("Content-Type", "application/json; charset=UTF-8");
        dpCreateMethod.setHeader("Accept", "application/json");
        dpCreateMethod.setHeader("Accept-Charset", "UTF-8");

        String status;

        Gson gson = new GsonBuilder().create();

        do {

            dpCreateMethod.setEntity(serialiseRequest(requestData));

            HttpResponse response = httpClient.execute(dpCreateMethod);

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new UnexpectedResponseException(generateRequestResponseString(dpCreateMethod, response));
            }

            String resultText = EntityUtils.toString(response.getEntity());

            LinkedTreeMap result;

            try {
                result = gson.fromJson(resultText, LinkedTreeMap.class);
            }
            catch (com.google.gson.JsonSyntaxException ex) {
                throw new UnexpectedResponseException(generateRequestResponseString(dpCreateMethod, response));
            }

            requestData.processData.VlocityDataPackId = (String)result.get("VlocityDataPackId");
            requestData.processData.VlocityDataPackData = null;

            status = (String)result.get("Status");

            if ("Error".equals(status)) {
                String message = (String)result.get("Message");
                throw new UnexpectedDataPackException("Unable to deploy datapack + " + requestData.processData.VlocityDataPackId + " for " + artifact.ArtifactType + " " + artifact.Key + ". Error message is \n" + message);
            }
        }
        while ("Ready".equals(status) || "InProgress".equals(status));*/

    }

}
