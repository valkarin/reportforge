(() => {
    const lightbox = document.getElementById('reportforge-lightbox');
    if (!lightbox) {
        return;
    }
    const preview = lightbox.querySelector('[data-lightbox-preview]');
    const title = lightbox.querySelector('[data-lightbox-title]');
    const zoomLabel = lightbox.querySelector('[data-lightbox-zoom-label]');
    const closeButton = lightbox.querySelector('[data-lightbox-action="close"]');
    let zoom = 1;
    let previousFocus = null;
    const minZoom = 0.5;
    const maxZoom = 4;
    const zoomStep = 0.25;

    const syncZoom = () => {
        preview.style.transform = `scale(${zoom})`;
        zoomLabel.textContent = `${Math.round(zoom * 100)}%`;
    };

    const setZoom = (nextZoom) => {
        zoom = Math.min(maxZoom, Math.max(minZoom, nextZoom));
        syncZoom();
    };

    const closeLightbox = () => {
        lightbox.hidden = true;
        lightbox.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('lightbox-open');
        preview.removeAttribute('src');
        preview.alt = '';
        title.textContent = 'Evidence Preview';
        if (previousFocus instanceof HTMLElement) {
            previousFocus.focus();
        }
    };

    const openLightbox = (trigger) => {
        const sourceImage = trigger.querySelector('img');
        if (!(sourceImage instanceof HTMLImageElement)) {
            return;
        }
        previousFocus = document.activeElement;
        preview.src = sourceImage.currentSrc || sourceImage.getAttribute('src') || '';
        preview.alt = sourceImage.getAttribute('alt') || 'Evidence Preview';
        title.textContent = trigger.getAttribute('data-lightbox-title') || preview.alt;
        zoom = 1;
        syncZoom();
        lightbox.hidden = false;
        lightbox.setAttribute('aria-hidden', 'false');
        document.body.classList.add('lightbox-open');
        closeButton.focus();
    };

    document.querySelectorAll('[data-lightbox-trigger]').forEach((trigger) => {
        trigger.addEventListener('click', () => openLightbox(trigger));
    });

    lightbox.addEventListener('click', (event) => {
        const target = event.target;
        if (!(target instanceof Element)) {
            return;
        }
        const actionElement = target.closest('[data-lightbox-action]');
        if (!actionElement) {
            return;
        }
        switch (actionElement.getAttribute('data-lightbox-action')) {
            case 'zoom-in':
                setZoom(zoom + zoomStep);
                break;
            case 'zoom-out':
                setZoom(zoom - zoomStep);
                break;
            case 'reset':
                setZoom(1);
                break;
            case 'close':
                closeLightbox();
                break;
            default:
                break;
        }
    });

    document.addEventListener('keydown', (event) => {
        if (lightbox.hidden) {
            return;
        }
        switch (event.key) {
            case 'Escape':
                event.preventDefault();
                closeLightbox();
                break;
            case '+':
            case '=':
                event.preventDefault();
                setZoom(zoom + zoomStep);
                break;
            case '-':
            case '_':
                event.preventDefault();
                setZoom(zoom - zoomStep);
                break;
            case '0':
                event.preventDefault();
                setZoom(1);
                break;
            default:
                break;
        }
    });
})();
