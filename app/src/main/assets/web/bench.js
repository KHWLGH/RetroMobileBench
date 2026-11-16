/* web-daily-v1. Portrait mobile feed + 480p/720p/1080p JPEG & PNG decode; ES3 and legacy DOM only. */
(function () {
    var work = document.getElementById('work'), corpus = window.RMB_CORPUS;
    var config, timer, resolution, clockName, lastClock, sequence = 0, images = [], hdImages = [], imageCursor = 0, viewportWidth, viewportHeight;
    var ids = ['page', 'todo', 'text', 'image'];
    var hdSpecs = [
        {id:'jpeg-854-480', src:'jpeg-854-480.jpg', w:854, h:480},
        {id:'png-854-480', src:'png-854-480.png', w:854, h:480},
        {id:'jpeg-1280-720', src:'jpeg-1280-720.jpg', w:1280, h:720},
        {id:'png-1280-720', src:'png-1280-720.png', w:1280, h:720},
        {id:'jpeg-1920-1080', src:'jpeg-1920-1080.jpg', w:1920, h:1080},
        {id:'png-1920-1080', src:'png-1920-1080.png', w:1920, h:1080}
    ];
    function assert(ok, reason) { if (!ok) { throw new Error(reason); } }
    function send(kind, value) {
        value = value || {}; value.kind = kind; value.nonce = config.nonce; value.sequence = ++sequence;
        window.prompt('RMB_WEB', JSON.stringify(value));
    }
    function fail(error) { send('error', {reason:String(error.message || error)}); }
    function guarded(fn) { return function () { try { fn(); } catch (e) { fail(e); } }; }
    function tick(fn) { window.setTimeout(guarded(fn), 0); }
    function now() {
        var value = timer();
        assert(isFinite(value) && (lastClock === null || value >= lastClock), 'clock_backwards_or_invalid');
        lastClock = value; return value;
    }
    function node(tag, text) { var n = document.createElement(tag); if (text !== undefined) { n.appendChild(document.createTextNode(text)); } return n; }
    function clear() { while (work.firstChild) { work.removeChild(work.firstChild); } }
    function viewport() {
        assert(window.innerWidth === viewportWidth && window.innerHeight === viewportHeight
            && Math.abs(work.offsetWidth - viewportWidth) <= 1, 'fullscreen_viewport_changed');
    }
    function layout() { viewport(); return work.offsetHeight; }
    function count(tag) { return work.getElementsByTagName(tag).length; }
    function page() {
        clear(); var i, c, img, meta, detail, fragment = document.createDocumentFragment();
        for (i = 0; i < 32; i++) {
            c = node('div'); c.className = 'card';
            img = node('img'); img.src = images[i % 6].src; img.width = 320; img.height = 96; c.appendChild(img);
            c.appendChild(node('h3', 'Feed post #' + i)); c.appendChild(node('span', 'Timeline update'));
            c.appendChild(node('p', 'Portrait mobile feed card with thumbnail and summary text.'));
            meta = node('div'); meta.className = 'meta'; detail = node('span', 'Topic ' + (i % 10)); meta.appendChild(detail);
            meta.appendChild(node('a', ' Open post')); c.appendChild(meta); fragment.appendChild(c);
        }
        work.appendChild(fragment); var height = layout();
        if (config.fault === 'bad_page') { work.removeChild(work.firstChild); }
        return [work.childNodes.length, count('*'), count('img'), height];
    }
    function todo() {
        clear(); var list = node('ul'), items = [], i, item, box, shown = [], enabled;
        work.appendChild(list);
        for (i = 0; i < 100; i++) {
            item = node('li'); box = node('input'); box.type = 'checkbox'; item.appendChild(box); item.appendChild(node('span', 'Feed item ' + i));
            list.appendChild(item); items.push({element:item, box:box, done:false});
        }
        var added = list.childNodes.length; layout();
        for (i = 0; i < 50; i++) { items[i].done = true; items[i].box.checked = true; items[i].element.className = 'done'; }
        if (config.fault === 'bad_todo') { items[0].done = false; items[0].box.checked = false; }
        var toggled = 0; for (i = 0; i < 100; i++) { if (items[i].done && items[i].box.checked && items[i].element.className === 'done') { toggled++; } }
        function filter(mode) {
            var visible = 0, n; for (n = 0; n < 100; n++) {
                enabled = mode === 'all' || (mode === 'active' ? !items[n].done : items[n].done);
                items[n].element.style.display = enabled ? 'block' : 'none'; if (enabled) { visible++; }
            } layout(); shown.push(visible);
        }
        filter('all'); filter('active'); filter('completed'); filter('all');
        for (i = 0; i < 100; i++) { list.removeChild(items[i].element); }
        layout(); var remaining = list.childNodes.length; clear();
        return [added, toggled, shown.join(','), remaining, work.childNodes.length];
    }
    function prepareText() { clear(); var i; for (i = 0; i < 24; i++) { work.appendChild(node('p', corpus.paragraph)); } layout(); }
    function text() {
        var ps = work.getElementsByTagName('p'), i, p, value, parts, j, replaced = 0, highlights = 0, collapsed = 0, expanded = 0;
        for (i = 0; i < 24; i++) {
            p = ps[i]; value = corpus.paragraph.replace(/daily web/g, function () { replaced++; return 'local web'; });
            while (p.firstChild) { p.removeChild(p.firstChild); }
            parts = value.split('task'); p.appendChild(document.createTextNode(parts[0]));
            for (j = 1; j < parts.length; j++) { var hit = node('span', 'task'); hit.className = 'hit'; p.appendChild(hit); p.appendChild(document.createTextNode(parts[j])); }
        }
        if (config.fault === 'bad_text') { ps[0].removeChild(ps[0].getElementsByTagName('span')[0]); }
        highlights = count('span'); layout();
        for (i = 0; i < 12; i++) { ps[i].style.display = 'none'; if (ps[i].offsetHeight === 0) { collapsed++; } }
        layout();
        for (i = 0; i < 12; i++) { ps[i].style.display = 'block'; if (ps[i].offsetHeight > 0) { expanded++; } }
        layout();
        for (i = 0; i < 24; i++) { p = ps[i]; while (p.firstChild) { p.removeChild(p.firstChild); } p.appendChild(document.createTextNode(corpus.paragraph)); }
        layout(); return [replaced, highlights, collapsed, expanded, work.innerText || work.textContent];
    }
    function decodeOneHd(spec, item) {
        var box = node('div'); box.className = 'viewer';
        box.appendChild(node('h3', 'HD Image Viewer · ' + spec.id));
        box.appendChild(node('p', spec.w + '×' + spec.h + ' full-frame Canvas decode & sample check'));
        var canvas = node('canvas');
        canvas.width = spec.w; canvas.height = spec.h;
        box.appendChild(canvas);
        work.appendChild(box);
        var ctx = canvas.getContext('2d');
        assert(ctx !== null, 'canvas_2d_context');
        ctx.drawImage(item, 0, 0, spec.w, spec.h);
        var p1 = ctx.getImageData(spec.w >> 2, spec.h >> 2, 1, 1).data;
        var p2 = ctx.getImageData(spec.w >> 1, spec.h >> 1, 1, 1).data;
        var p3 = ctx.getImageData((spec.w * 3) >> 2, (spec.h * 3) >> 2, 1, 1).data;
        var alphaOk = (p1[3] > 0 && p2[3] > 0 && p3[3] > 0);
        var rgbSum = p1[0] + p1[1] + p1[2] + p2[0] + p2[1] + p2[2] + p3[0] + p3[1] + p3[2];
        var h = layout();
        /* Release full-resolution backing buffer immediately after layout & verification to prevent legacy OOM. */
        canvas.width = 1; canvas.height = 1;
        return alphaOk && rgbSum > 0 && h > 0 ? spec.w * spec.h : 0;
    }
    function imageTask() {
        clear();
        var idx = imageCursor % hdSpecs.length;
        imageCursor++;
        var spec = hdSpecs[idx], item = hdImages[idx];
        var pixels = decodeOneHd(spec, item);
        if (config.fault === 'bad_data' || config.fault === 'bad_image') { pixels = 0; }
        return [spec.w, spec.h, pixels, count('canvas')];
    }
    var tasks = [page, todo, text, imageTask];
    function validate(index, proof) {
        if (index === 0) { assert(proof[0] === 32 && proof[1] === 256 && proof[2] === 32 && proof[3] > 0, 'page_correctness'); }
        if (index === 1) { assert(proof[0] === 100 && proof[1] === 50 && proof[2] === '100,50,50,100' && proof[3] === 0 && proof[4] === 0, 'todo_correctness'); }
        if (index === 2) {
            assert(proof[0] === 48 && proof[1] === 240 && proof[2] === 12 && proof[3] === 12, 'text_correctness');
            assert(proof[4].replace(/[\r\n]/g, '') === new Array(25).join(corpus.paragraph), 'text_restore');
        }
        if (index === 3) {
            assert(proof[0] >= 854 && proof[1] >= 480 && proof[2] === proof[0] * proof[1] && proof[3] === 1, 'image_decode_correctness');
        }
    }
    function reset(index) { imageCursor = 0; if (index === 2) { prepareText(); } else { clear(); layout(); } }
    function formal(index) {
        if (index === 4) { send('done'); return; }
        if (config.fault === 'late_text' && index === 2) { throw new Error('injected_late_text_failure'); }
        reset(index); send('begin', {index:index, id:ids[index]});
        if (config.fault === 'hang_measure') { while (true) {} }
        var proofs = [], started, ended;
        tick(function () {
            started = now();
            function unit() {
                proofs.push(tasks[index]());
                if (proofs.length < 20) { tick(unit); return; }
                ended = now();
                var elapsed = ended - started;
                if (config.fault === 'clock_zero') { elapsed = 0; }
                if (config.fault === 'clock_backward') { elapsed = -1; }
                assert(isFinite(elapsed) && elapsed > 0 && elapsed >= resolution * 20, 'clock_insufficient_ticks');
                var n; for (n = 0; n < proofs.length; n++) { validate(index, proofs[n]); }
                send('metric', {index:index, id:ids[index], units:20, total_ms:elapsed, unit_ms:elapsed / 20,
                    proof:{validated_units:proofs.length, checkpoint:'offsetHeight_and_canvas_getImageData', final_elements:count('*')}});
                proofs = null; reset(index); tick(function () { formal(index + 1); });
            }
            unit();
        });
    }
    function prewarm(index) {
        if (index === 4) { clear(); send('prepared', {clock_source:clockName, clock_resolution_ms:resolution,
            viewport_width:window.innerWidth, viewport_height:window.innerHeight, dpr:window.devicePixelRatio || 1,
            user_agent:navigator.userAgent, text_css:'12px/18px sans-serif', scheduling:'setTimeout(0) between units; included'});
            if (config.fault === 'pause_before_formal') { window.setTimeout(guarded(function () { formal(0); }), 5000); }
            else { tick(function () { formal(0); }); } return; }
        reset(index);
        if (index === 3) {
            var k;
            for (k = 0; k < hdSpecs.length; k++) {
                var p = imageTask();
                validate(3, p);
            }
        } else {
            var proof = tasks[index]();
            validate(index, proof);
        }
        reset(index); tick(function () { prewarm(index + 1); });
    }
    function clockProbe() {
        var perf = window.performance;
        if (perf && typeof perf.now === 'function') { clockName = 'performance.now'; timer = function () { return perf.now(); }; }
        else { clockName = 'Date.getTime'; timer = function () { return new Date().getTime(); }; }
        lastClock = null; resolution = Infinity;
        var before = now(), observed = 0, attempts = 0;
        function probe() {
            var current = now(), delta = current - before;
            if (delta > 0) { if (delta < resolution) { resolution = delta; } observed++; before = current; }
            attempts++;
            if (observed >= 8) { resolution = Math.max(0.000001, Math.round(resolution * 1000000) / 1000000); prewarm(0); return; }
            assert(attempts < 200000, 'clock_no_observable_ticks');
            if (attempts % 1000 === 0) { tick(probe); } else { probe(); }
        }
        probe();
    }
    window.RMB = {start:function (value) {
        config = value;
        try {
            viewportWidth = window.innerWidth; viewportHeight = window.innerHeight;
            assert(viewportWidth > 0 && viewportHeight > 0 && viewportHeight >= viewportWidth, 'fullscreen_portrait_viewport');
            viewport();
            if (config.fault === 'hang_prepare') { while (true) {} }
            var loaded = 0, totalImages = 6 + hdSpecs.length, i;
            function checkAllLoaded() {
                loaded++;
                if (loaded === totalImages) { tick(clockProbe); }
            }
            for (i = 0; i < 6; i++) { (function (index) {
                var image = new Image(); images.push(image);
                image.onload = guarded(function () { assert(image.width === 320 && image.height === 180, 'image_dimensions'); checkAllLoaded(); });
                image.onerror = function () { fail('image_preload'); };
                image.src = 'image' + index + (index < 4 ? '.jpg' : '.png');
            }(i)); }
            for (i = 0; i < hdSpecs.length; i++) { (function (index) {
                var spec = hdSpecs[index], hd = new Image(); hdImages.push(hd);
                hd.onload = guarded(function () { assert(hd.width === spec.w && hd.height === spec.h, 'hd_image_dimensions:' + spec.id); checkAllLoaded(); });
                hd.onerror = function () { fail('hd_image_preload:' + spec.id); };
                hd.src = spec.src;
            }(i)); }
        } catch (error) { fail(error); }
    }};
    window.prompt('RMB_WEB_READY', '');
}());
