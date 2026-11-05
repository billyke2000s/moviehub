"""Small bounded HTML tree for CineStream's heading/sibling catalogue protocols."""
from html.parser import HTMLParser


class Node:
    def __init__(self, tag='', attrs=(), parent=None):
        self.tag = tag
        self.attrs = dict(attrs)
        self.parent = parent
        self.children = []

    def all(self, tag=None, cls=None, **attrs):
        for child in self.children:
            if not isinstance(child, Node):
                continue
            if ((tag is None or child.tag in tag.split(',')) and
                    (cls is None or set(cls.split()) <= set((child.attrs.get('class') or '').split())) and
                    all(child.attrs.get(key.replace('_', '-')) == value for key, value in attrs.items())):
                yield child
            yield from child.all(tag, cls, **attrs)

    def first(self, tag=None, cls=None, **attrs):
        return next(self.all(tag, cls, **attrs), None)

    def text(self):
        return ' '.join(' '.join(child.text() if isinstance(child, Node) else child
                                 for child in self.children).split())

    def siblings(self):
        if self.parent is None:
            return
        found = False
        for child in self.parent.children:
            if child is self:
                found = True
            elif found and isinstance(child, Node):
                yield child

    def next(self):
        return next(self.siblings(), None)


class Tree(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta',
            'param', 'source', 'track', 'wbr'}

    def __init__(self, page):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.nodes = 0
        self.feed(page)
        self.close()

    def handle_starttag(self, tag, attrs):
        self.nodes += 1
        if self.nodes > 60000 or len(self.stack) > 96:
            raise ValueError('Catalogue HTML limit')
        # Common optional closing tags in WordPress bodies.
        if tag in ('p', 'h2', 'h3', 'h4', 'h5', 'h6', 'div') and self.stack[-1].tag == 'p':
            self.stack.pop()
        node = Node(tag, attrs, self.stack[-1])
        self.stack[-1].children.append(node)
        if tag not in self.VOID:
            self.stack.append(node)

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                break

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def tree(page):
    return Tree(page).root
