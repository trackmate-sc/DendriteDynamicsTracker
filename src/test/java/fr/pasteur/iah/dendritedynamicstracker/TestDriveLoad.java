package fr.pasteur.iah.dendritedynamicstracker;

import fiji.plugin.trackmate.LoadTrackMatePlugIn;
import net.imagej.ImageJ;

public class TestDriveLoad
{

	public static void main( final String[] args )
	{
		final ImageJ ij = new ImageJ();
		ij.launch( args );
		new LoadTrackMatePlugIn().run( "samples/1-1 96h 3min interval skeletonize-1.xml" );
	}

}
