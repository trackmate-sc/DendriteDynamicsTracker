package fr.pasteur.iah.dendritedynamicstracker;

import java.io.IOException;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;

import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.SkeletonAnalysis;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.SkeletonAnalysis.Junction;

public class TestSkeletonAnalyzer
{

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static void main( final String[] args ) throws IOException
	{
		final ImageJ ij = new ImageJ();
		ij.launch( args );

		final String path = "samples/Example3-singleframe.tif";
		final Dataset dataset = ( Dataset ) ij.io().open( path );
		ij.ui().show( dataset );

		final SkeletonAnalysis analysis = SkeletonAnalyzer.analyze( ( RandomAccessibleInterval ) dataset.getImgPlus() );
		ij.ui().show( analysis.taggedSkeleton );

		for ( final Junction junction : analysis.getJunctions() )
			System.out.println( junction );
	}
}
