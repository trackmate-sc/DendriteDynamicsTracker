package fr.pasteur.iah.dendritedynamicstracker;

import java.io.IOException;

import ij.io.Opener;
import net.imagej.ImageJ;

public class TestDriveCommand
{

	public static void main( final String[] args ) throws IOException
	{
		final ImageJ ij = new ImageJ();
		ij.launch( args );
		final String traceImgName = "samples/Example3-dendrites_traced-1.tif";
		new Opener().openImage( traceImgName ).show();
		ij.command().run( DendriteDynamicsTrackerCommand.class, true );
	}

}
