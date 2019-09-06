package fr.pasteur.iah.dendritedynamicstracker;

import java.io.IOException;

import net.imagej.ImageJ;

public class TestDriveCommand
{

	public static void main( final String[] args ) throws IOException
	{
		final String traceImgName = "samples/Example3-dendrites_traced.tif";

		final ImageJ ij = new ImageJ();
		ij.launch( args );

		final Object obj = ij.io().open( traceImgName );
		ij.ui().show( obj );

		ij.command().run( DendriteDynamicsTrackerCommand.class, true );
	}

}
