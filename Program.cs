using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace CoverArtVideoMaker;
static class Program {
 [STAThread] static void Main(){ ApplicationConfiguration.Initialize(); Application.Run(new MainForm()); }
}
class MainForm : Form {
 TextBox art=new(){ReadOnly=true}, audio=new(){ReadOnly=true}, output=new(){ReadOnly=true};
 ComboBox preset=new(){DropDownStyle=ComboBoxStyle.DropDownList}; CheckBox animate=new(){Text="Subtle animated zoom",Checked=true}; ProgressBar progress=new(){Style=ProgressBarStyle.Marquee,Visible=false}; Button make=new(){Text="CREATE VIDEO",Height=46};
 public MainForm(){Text="Cover Art Video Maker";Width=720;Height=500;MinimumSize=new(620,430);Font=new("Segoe UI",10);BackColor=Color.FromArgb(24,24,28);ForeColor=Color.White; var p=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new(24),ColumnCount=1,RowCount=12}; Controls.Add(p);
  var title=new Label{Text="COVER ART VIDEO MAKER",Font=new("Segoe UI Semibold",20),AutoSize=true};p.Controls.Add(title);p.Controls.Add(new Label{Text="Turn cover artwork + audio into a ready-to-upload MP4.",AutoSize=true});
  AddPicker(p,"Cover art (JPG / PNG)",art,PickArt); AddPicker(p,"Audio (MP3 / WAV / FLAC / M4A)",audio,PickAudio); AddPicker(p,"Save MP4 as",output,PickOutput);
  preset.Items.AddRange(["YouTube — 1920×1080","Square — 1080×1080","Vertical — 1080×1920"]);preset.SelectedIndex=0;p.Controls.Add(preset);p.Controls.Add(animate); make.Click+=Render;p.Controls.Add(make);p.Controls.Add(progress);
 }
 void AddPicker(TableLayoutPanel p,string label,TextBox box,EventHandler click){p.Controls.Add(new Label{Text=label,AutoSize=true,Margin=new Padding(0,12,0,2)});var row=new TableLayoutPanel{Dock=DockStyle.Top,ColumnCount=2,Height=36};row.ColumnStyles.Add(new(SizeType.Percent,100));row.ColumnStyles.Add(new(SizeType.Absolute,100));box.Dock=DockStyle.Fill;var b=new Button{Text="Browse…",Dock=DockStyle.Fill};b.Click+=click;row.Controls.Add(box);row.Controls.Add(b);p.Controls.Add(row);}
 void PickArt(object? s,EventArgs e){using var d=new OpenFileDialog{Filter="Images|*.png;*.jpg;*.jpeg;*.webp"};if(d.ShowDialog()==DialogResult.OK)art.Text=d.FileName;}
 void PickAudio(object? s,EventArgs e){using var d=new OpenFileDialog{Filter="Audio|*.mp3;*.wav;*.flac;*.m4a;*.aac;*.ogg"};if(d.ShowDialog()==DialogResult.OK)audio.Text=d.FileName;}
 void PickOutput(object? s,EventArgs e){using var d=new SaveFileDialog{Filter="MP4 Video|*.mp4",DefaultExt="mp4"};if(d.ShowDialog()==DialogResult.OK)output.Text=d.FileName;}
 async void Render(object? s,EventArgs e){if(!File.Exists(art.Text)||!File.Exists(audio.Text)){MessageBox.Show("Choose cover art and audio first.");return;} if(string.IsNullOrWhiteSpace(output.Text)) PickOutput(s,e);if(string.IsNullOrWhiteSpace(output.Text))return;string ff=FindFfmpeg();if(ff==""){MessageBox.Show("FFmpeg was not found. Reinstall the app or place ffmpeg.exe beside CoverArtVideoMaker.exe.");return;}
  var (w,h)=preset.SelectedIndex switch{1=>(1080,1080),2=>(1080,1920),_=>(1920,1080)};string vf=animate.Checked?$"scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},zoompan=z='min(zoom+0.00015,1.08)':d=1:s={w}x{h}:fps=30,format=yuv420p":$"scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:black,format=yuv420p";
  string args=$"-y -loop 1 -i \"{art.Text}\" -i \"{audio.Text}\" -vf \"{vf}\" -c:v libx264 -preset medium -crf 18 -c:a aac -b:a 320k -shortest -movflags +faststart \"{output.Text}\""; make.Enabled=false;progress.Visible=true;
  try{var psi=new ProcessStartInfo(ff,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true};using var pr=Process.Start(psi)!;await pr.StandardError.ReadToEndAsync();await pr.WaitForExitAsync();if(pr.ExitCode==0)MessageBox.Show("Video created successfully!\n\n"+output.Text,"Finished");else MessageBox.Show("FFmpeg could not create the video.","Render error");}catch(Exception ex){MessageBox.Show(ex.Message,"Error");}finally{progress.Visible=false;make.Enabled=true;}}
 string FindFfmpeg(){foreach(var x in new[]{Path.Combine(AppContext.BaseDirectory,"ffmpeg.exe"),"ffmpeg.exe"})try{var p=Process.Start(new ProcessStartInfo(x,"-version"){UseShellExecute=false,CreateNoWindow=true});p!.WaitForExit(2000);if(p.ExitCode==0)return x;}catch{}return "";}
}
