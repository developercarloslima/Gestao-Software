package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.TowPortalDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.TowPortalService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/tow")
public class TowPortalController {
    private final TowPortalService service;
    public TowPortalController(TowPortalService service){this.service=service;}

    @GetMapping("/template") public List<TowTemplateItem> template(Authentication auth){return service.template(principal(auth));}
    @GetMapping("/records") public List<TowQueueSummary> list(@RequestParam(required=false) String q,@RequestParam(required=false) String status,Authentication auth){return service.list(principal(auth),q,status);}
    @PostMapping("/records") public TowRecordDetail create(@Valid @RequestBody CreateTowRecordRequest request,Authentication auth){return service.create(request,principal(auth));}
    @GetMapping("/records/{id}") public TowRecordDetail detail(@PathVariable UUID id,Authentication auth){return service.detail(id,principal(auth));}
    @PatchMapping("/records/{recordId}/items/{itemId}") public TowRecordDetail updateItem(@PathVariable UUID recordId,@PathVariable UUID itemId,@Valid @RequestBody UpdateTowItemRequest request,Authentication auth){return service.updateItem(recordId,itemId,request,principal(auth));}
    @PostMapping(value="/records/{recordId}/photos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public TowRecordDetail uploadPhotos(@PathVariable UUID recordId,@RequestParam String photoKind,@RequestParam(required=false) UUID checklistItemId,@RequestParam(required=false) String notes,@RequestParam("files") List<MultipartFile> files,Authentication auth){return service.uploadPhotos(recordId,photoKind,checklistItemId,files,notes,principal(auth));}
    @GetMapping("/records/{recordId}/photos/{photoId}") public ResponseEntity<byte[]> photo(@PathVariable UUID recordId,@PathVariable UUID photoId,Authentication auth){
        TowPhotoContent c=service.photo(recordId,photoId,principal(auth)); MediaType t;try{t=MediaType.parseMediaType(c.contentType());}catch(Exception e){t=MediaType.APPLICATION_OCTET_STREAM;}
        return ResponseEntity.ok().contentType(t).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(c.fileName(),StandardCharsets.UTF_8).build().toString()).body(c.bytes());
    }
    @DeleteMapping("/records/{recordId}/photos/{photoId}") public TowRecordDetail deletePhoto(@PathVariable UUID recordId,@PathVariable UUID photoId,Authentication auth){return service.deletePhoto(recordId,photoId,principal(auth));}
    @PostMapping("/records/{recordId}/complete") public TowRecordDetail complete(@PathVariable UUID recordId,Authentication auth){return service.complete(recordId,principal(auth));}
    private PortalPrincipal principal(Authentication auth){return (PortalPrincipal)auth.getPrincipal();}
}
