-- WordPress's upload options of a multisite network, as its network setup writes them; options the network already
-- has are kept. upload_space_check_disabled is "1", so the space and file size limits are off until it is changed.
-- upload_filetypes is every extension of the default upload type list but swf and exe, each once, in list order: the
-- list of the administrator who installs the network (without it a network would only allow jpg jpeg png gif).
insert into qp_network_option (name, value)
select d.name, d.value
from (
    select 'upload_space_check_disabled' as name, '"1"' as value
    union all select 'fileupload_maxk', '1500'
    union all select 'blog_upload_space', '100'
    union all select 'upload_filetypes', '"jpg jpeg jpe gif png bmp tiff tif webp avif ico heic heif heics heifs asf asx wmv wmx wm avi divx flv mov qt mpeg mpg mpe mp4 m4v ogv webm mkv 3gp 3gpp 3g2 3gp2 txt asc c cc h srt csv tsv ics rtx css htm html vtt dfxp mp3 m4a m4b aac ra ram wav x-wav ogg oga flac mid midi wma wax mka rtf js pdf class tar zip gz gzip rar 7z psd xcf doc pot pps ppt wri xla xls xlt xlw mdb mpp docx docm dotx dotm xlsx xlsm xlsb xltx xltm xlam pptx pptm ppsx ppsm potx potm ppam sldx sldm onetoc onetoc2 onetmp onepkg oxps xps odt odp ods odg odc odb odf wp wpd key numbers pages"'
) d
where not exists (
    select 1 from qp_network_option o where o.name = convert(d.name using utf8mb4) collate utf8mb4_bin);
