package storage;

import client.ArtifactTypeEnum;
import client.VlocityArtifact;

/**
 * Created by Derek on 26/08/2016.
 */
public interface ICommitHandler {

    void HandleCommit(VlocityArtifact artifact);

}
