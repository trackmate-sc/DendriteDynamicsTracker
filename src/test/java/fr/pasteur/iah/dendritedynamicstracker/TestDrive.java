package fr.pasteur.iah.dendritedynamicstracker;

import java.io.IOException;

import net.imagej.ImageJ;

public class TestDrive
{

	public static void main( final String[] args ) throws IOException
	{
		final String traceImgName = "samples/traces2.tif";

		final ImageJ ij = new ImageJ();
		ij.launch( args );

		final Object obj = ij.io().open( traceImgName );
		ij.ui().show( obj );

		new DendriteDynamicsTrackerPlugIn().run( null );

	}

}
