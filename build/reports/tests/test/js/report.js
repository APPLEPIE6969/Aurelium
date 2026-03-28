(function (window, document) {
    "use strict";

    var tabs = {};

    /**
     * Set an element's class value, writing to the `className` attribute if present or to `class` otherwise.
     * @param {Element} element - The DOM element whose class attribute will be set.
     * @param {string} classValue - The class string to assign to the element.
     */
    function changeElementClass(element, classValue) {
        if (element.getAttribute("className")) {
            element.setAttribute("className", classValue);
        } else {
            element.setAttribute("class", classValue);
        }
    }

    /**
     * Get the class value for a DOM element, preferring the "className" attribute over "class".
     * @param {Element} element - The DOM element to read.
     * @returns {string|null} The class attribute value from "className" if present, otherwise from "class", or null if neither is set.
     */
    function getClassAttribute(element) {
        if (element.getAttribute("className")) {
            return element.getAttribute("className");
        } else {
            return element.getAttribute("class");
        }
    }

    /**
     * Adds a CSS class (or classes) to an element's existing class list.
     * Appends the provided classValue to whatever class attribute/name the element uses, separated by a single space.
     * @param {Element} element - The target DOM element to modify.
     * @param {string} classValue - One or more class names (space-separated) to append to the element.
     */
    function addClass(element, classValue) {
        changeElementClass(element, getClassAttribute(element) + " " + classValue);
    }

    /**
     * Remove occurrences of the given class string from an element's class attribute.
     * @param {Element} element - The element whose class attribute will be modified.
     * @param {string} classValue - The class string (or substring) to remove from the element's class list.
     */
    function removeClass(element, classValue) {
        changeElementClass(element, getClassAttribute(element).replace(classValue, ""));
    }

    /**
     * Initialize the tabbed interface: locate the tabs container, populate the shared `tabs` object with tab elements, titles, and header elements, bind select/deselect handlers, and select the first tab.
     * @returns {boolean} `true` if initialization completed successfully.
     */
    function initTabs() {
        var container = document.getElementById("tabs");

        tabs.tabs = findTabs(container);
        tabs.titles = findTitles(tabs.tabs);
        tabs.headers = findHeaders(container);
        tabs.select = select;
        tabs.deselectAll = deselectAll;
        tabs.select(0);

        return true;
    }

    /**
     * Retrieve the line-wrapping toggle checkbox element.
     * @returns {HTMLInputElement|null} The checkbox input with id "line-wrapping-toggle", or `null` if not present.
     */
    function getCheckBox() {
        return document.getElementById("line-wrapping-toggle");
    }

    /**
     * Retrieves the label element associated with the line-wrapping checkbox.
     * @return {HTMLElement|null} The label element with id "label-for-line-wrapping-toggle", or `null` if it does not exist.
     */
    function getLabelForCheckBox() {
        return document.getElementById("label-for-line-wrapping-toggle");
    }

    /**
     * Finds code block spans inside the element with id "tabs".
     *
     * Searches the #tabs container for <span> elements whose class contains "code" and returns them in an array.
     * @returns {HTMLSpanElement[]} An array of matching <span> elements whose class includes "code".
     */
    function findCodeBlocks() {
        var spans = document.getElementById("tabs").getElementsByTagName("span");
        var codeBlocks = [];
        for (var i = 0; i < spans.length; ++i) {
            if (spans[i].className.indexOf("code") >= 0) {
                codeBlocks.push(spans[i]);
            }
        }
        return codeBlocks;
    }

    /**
     * Apply a callback to every code block found inside the tabs container.
     * @param {function(Element, string)} operation - Function invoked for each code block; called with the code block element and the string `"wrapped"`.
     */
    function forAllCodeBlocks(operation) {
        var codeBlocks = findCodeBlocks();

        for (var i = 0; i < codeBlocks.length; ++i) {
            operation(codeBlocks[i], "wrapped");
        }
    }

    /**
     * Toggle line-wrapping on code blocks based on the line-wrapping checkbox state.
     *
     * When the checkbox is checked, adds the "wrapped" class to all code block spans; when unchecked, removes the "wrapped" class from all code block spans.
     */
    function toggleLineWrapping() {
        var checkBox = getCheckBox();

        if (checkBox.checked) {
            forAllCodeBlocks(addClass);
        } else {
            forAllCodeBlocks(removeClass);
        }
    }

    /**
     * Initialize the line-wrapping UI control when code blocks exist on the page.
     *
     * If any code blocks are found, assigns the checkbox click handler to toggle
     * line wrapping, ensures the checkbox is unchecked, and reveals its label.
     */
    function initControls() {
        if (findCodeBlocks().length > 0) {
            var checkBox = getCheckBox();
            var label = getLabelForCheckBox();

            checkBox.onclick = toggleLineWrapping;
            checkBox.checked = false;

            removeClass(label, "hidden");
         }
    }

    /**
     * Handle a header click by selecting the corresponding tab.
     *
     * Extracts the target tab identifier from the clicked header element's id and activates the matching tab.
     * @returns {boolean} `false` to prevent the default link navigation behavior.
     */
    function switchTab() {
        var id = this.id.substr(1);

        for (var i = 0; i < tabs.tabs.length; i++) {
            if (tabs.tabs[i].id === id) {
                tabs.select(i);
                break;
            }
        }

        return false;
    }

    /**
     * Selects the tab at the given index and updates the tab headers and content to reflect that selection.
     *
     * This function expects to be called with `this` bound to the shared `tabs` object; it clears other selections,
     * marks the specified tab and its header as selected, and replaces the header content with an H2 containing the tab's title.
     *
     * @param {number} i - Index of the tab to select within the `tabs` collection.
     */
    function select(i) {
        this.deselectAll();

        changeElementClass(this.tabs[i], "tab selected");
        changeElementClass(this.headers[i], "selected");

        while (this.headers[i].firstChild) {
            this.headers[i].removeChild(this.headers[i].firstChild);
        }

        var h2 = document.createElement("H2");

        h2.appendChild(document.createTextNode(this.titles[i]));
        this.headers[i].appendChild(h2);
    }

    /**
     * Deselects every tab and reconstructs the header entries as clickable links.
     *
     * For each tab this updates the tab and header elements' classes to a deselected state,
     * removes any existing header children, and inserts an anchor with id `ltab{i}`, href `#tab{i}`,
     * an onclick handler that switches tabs, and the displayed title text.
     *
     * @this {Object} tabs - Object containing arrays: `tabs` (tab content elements), `headers` (header containers), and `titles` (title strings).
     */
    function deselectAll() {
        for (var i = 0; i < this.tabs.length; i++) {
            changeElementClass(this.tabs[i], "tab deselected");
            changeElementClass(this.headers[i], "deselected");

            while (this.headers[i].firstChild) {
                this.headers[i].removeChild(this.headers[i].firstChild);
            }

            var a = document.createElement("A");

            a.setAttribute("id", "ltab" + i);
            a.setAttribute("href", "#tab" + i);
            a.onclick = switchTab;
            a.appendChild(document.createTextNode(this.titles[i]));

            this.headers[i].appendChild(a);
        }
    }

    /**
     * Find child DIV elements whose class contains "tab" within the given container.
     * @param {Element} container - The parent DOM node to search.
     * @returns {Element[]} An array of DIV elements whose class string includes "tab".
     */
    function findTabs(container) {
        return findChildElements(container, "DIV", "tab");
    }

    /**
     * Locate the list item header elements for tab links within the given container.
     * @param {Element} container - DOM element that contains the tab links list.
     * @return {Element[]} Array of <li> elements found inside the first <ul> with class "tabLinks".
     */
    function findHeaders(container) {
        var owner = findChildElements(container, "UL", "tabLinks");
        return findChildElements(owner[0], "LI", null);
    }

    /**
     * Extracts and returns the title text from each tab's first H2 header and removes those headers from the tabs.
     * @param {Element[]} tabs - Array of tab container elements to scan for an H2 title.
     * @returns {string[]} An array of title strings, one per tab, in the same order as the input.
     */
    function findTitles(tabs) {
        var titles = [];

        for (var i = 0; i < tabs.length; i++) {
            var tab = tabs[i];
            var header = findChildElements(tab, "H2", null)[0];

            header.parentNode.removeChild(header);

            if (header.innerText) {
                titles.push(header.innerText);
            } else {
                titles.push(header.textContent);
            }
        }

        return titles;
    }

    /**
     * Collects direct child elements of a container that match a specified node name and optional class substring.
     * @param {Node} container - Parent node whose direct children will be inspected.
     * @param {string} name - The nodeName to match (e.g., "DIV", "H2"); comparison is exact.
     * @param {string} [targetClass] - Optional substring that must appear in a child's `className`; if omitted, no class filtering is applied.
     * @returns {Array<Element>} An array of matching child Element nodes (only direct children, not descendants).
     */
    function findChildElements(container, name, targetClass) {
        var elements = [];
        var children = container.childNodes;

        for (var i = 0; i < children.length; i++) {
            var child = children.item(i);

            if (child.nodeType === 1 && child.nodeName === name) {
                if (targetClass && child.className.indexOf(targetClass) < 0) {
                    continue;
                }

                elements.push(child);
            }
        }

        return elements;
    }

    // Entry point.

    window.onload = function() {
        initTabs();
        initControls();
    };
} (window, window.document));