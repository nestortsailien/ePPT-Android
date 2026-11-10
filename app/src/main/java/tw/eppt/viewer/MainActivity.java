package tw.eppt.viewer;
import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import java.util.Arrays;
public class MainActivity extends Activity {
 ImageView image; String[] slides; int index=0; float downX,downY;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894|1024|512); image=new ImageView(this);image.setBackgroundColor(0xff000000);image.setScaleType(ImageView.ScaleType.FIT_CENTER);setContentView(image);try{slides=getAssets().list("slides");Arrays.sort(slides);}catch(Exception e){slides=new String[0];}show();}
 void show(){if(slides.length==0)return;try(InputStream s=getAssets().open("slides/"+slides[index])){image.setImageBitmap(BitmapFactory.decodeStream(s));}catch(Exception e){}}
 @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();return true;}if(e.getAction()==MotionEvent.ACTION_UP){float d=e.getX()-downX;if(Math.abs(d)>50){index=Math.max(0,Math.min(slides.length-1,index+(d<0?1:-1)));show();}else if(e.getX()>image.getWidth()*0.65){index=Math.min(slides.length-1,index+1);show();}else if(e.getX()<image.getWidth()*0.35){index=Math.max(0,index-1);show();}return true;}return true;}
}